package com.hmdp.config;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.Set;

/**
 * 业务异常的处理器，与 {@link WebExceptionAdvice} 分开两个类。
 * <p>
 * 为什么要拆：两者面对的是完全不同的两类失败。
 * <ul>
 *   <li>BizException / DuplicateKeyException 是「预期内的失败」，属于业务分支，
 *       只回提示语、打 warn、不打堆栈。一天出现几千次也正常。</li>
 *   <li>RuntimeException 是「预期外的故障」，属于 bug 或环境问题，必须打完整堆栈，
 *       并且对外只说「服务器异常」——把 SQL、类名、内网地址抛给前端等于泄露结构。</li>
 * </ul>
 * 混在一个类里，日志级别和返回文案就会被搅在一起：要么业务异常刷满 error 日志，
 * 要么真正的 NPE 被当成「参数不合法」悄悄吞掉。
 * <p>
 * 两个 advice 同时存在不会有歧义：Spring 在所有 advice 里挑匹配得最具体的那个
 * ExceptionHandler，BizException 比 RuntimeException 近，自然赢。
 * <p>
 * <strong>参数校验为什么也放在这里</strong>：校验失败是「调用方改参数就能成功」的预期内失败，
 * 属于 41xx 段。放进 {@link WebExceptionAdvice} 会被当成服务端故障打 error、计入 5xx 故障率，
 * 结果是一天几百次用户手滑把真正的故障淹没掉。
 * <p>
 * 三种校验异常的分工（面试高频，别混）：
 * <ul>
 *   <li>{@link MethodArgumentNotValidException}：{@code @RequestBody} + {@code @Valid} 校验失败</li>
 *   <li>{@link BindException}：表单 / {@code @ModelAttribute} 绑定校验失败</li>
 *   <li>{@link ConstraintViolationException}：方法参数上的 {@code @Min} 等约束失败，
 *       要求 Controller 类上有 {@code @Validated}（否则根本不校验）</li>
 * </ul>
 * 前两者共用 {@link BindingResult} 抽象，所以取文案的逻辑能复用；
 * 第三个是 javax.validation 体系，取错误的方式完全不同，必须单独写。
 * <p>
 * 注意：本项目的 Spring 是 5.2 系列，{@code MethodArgumentNotValidException} 与
 * {@code BindException} <strong>不是父子关系</strong>（5.3 之后才改成 extends），
 * 所以两个 handler 必须各写一个，不能指望一个兜住另一个。
 */
@Slf4j
@RestControllerAdvice
public class BizExceptionAdvice {

    /**
     * 业务异常：可预期的失败（库存不足、券已过期、不属于你的券等）。
     * <p>
     * 注意：@ExceptionHandler 里声明的类型必须和形参类型一致。
     * 写成 Exception.class 而形参是 BizException，Spring 解析不出参数，
     * 会在处理异常时再抛 IllegalStateException，原始错误被彻底掩盖，
     * 前端只会看到一个跟真实原因无关的 500。
     * <p>
     * 返回时必须把 errorCode 和 message <strong>两个都传</strong>：
     * 只传 {@code fail(e.getErrorCode())} 会丢掉调用方自定义的文案，
     * 线上排查能看到「库存不足」而不是「库存不足：voucherId=10, userId=1001」。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result> handleBizException(BizException e) {
        log.warn("业务异常：code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(ApiStatus.of(e.getErrorCode()))
                .body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    /**
     * 唯一索引冲突的兜底。
     * <p>
     * 领券那条链路里已经 try-catch 了自己处理（要补偿库存和计数），
     * 这里兜的是没显式 catch 的地方——比如会员月度发放。
     * 用户看到的应该是「别重复提交」，不是「服务器异常」。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突：{}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(ApiStatus.of(ErrorCode.BIZ_GENERAL))
                .body(Result.fail(ErrorCode.BIZ_GENERAL, "请勿重复提交"));
    }

    /** {@code @RequestBody} + {@code @Valid}：JSON 请求体的字段校验 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String msg = firstFieldMessage(ex.getBindingResult());
        log.warn("参数校验失败：{}", msg);
        return ResponseEntity.status(ApiStatus.of(ErrorCode.PARAM_INVALID))
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    /** 表单 / {@code @ModelAttribute} 的绑定校验 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result> handleBindException(BindException ex) {
        String msg = firstFieldMessage(ex.getBindingResult());
        log.warn("参数绑定校验失败：{}", msg);
        return ResponseEntity.status(ApiStatus.of(ErrorCode.PARAM_INVALID))
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    /** 方法参数上的约束（{@code @Min} / {@code @NotNull}），需 Controller 类上有 {@code @Validated} */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = firstViolationMessage(ex);
        log.warn("方法参数校验失败：{}", msg);
        return ResponseEntity.status(ApiStatus.of(ErrorCode.PARAM_INVALID))
                .body(Result.fail(ErrorCode.PARAM_INVALID, msg));
    }

    /**
     * 从 BindingResult 里取一条能直接展示给用户的文案，格式 {@code "字段名: 提示"}。
     * <p>
     * 三级判空不是防御性冗余，每个分支都真实存在：
     * <ol>
     *   <li>普通字段约束（{@code @NotBlank name}）→ 走 {@code getFieldError()}</li>
     *   <li>类级约束（{@code @AssertTrue} 或自定义类级 ConstraintValidator）只产生全局错误，
     *       此时 {@code getFieldError()} 返回 <strong>null</strong>，
     *       直接链式调用 {@code getFieldError().getDefaultMessage()} 就是 NPE</li>
     *   <li>两者都没有 → 回退枚举文案，保证任何情况下都不返回 null</li>
     * </ol>
     * 这个 NPE 会发生在 {@code @ExceptionHandler} 内部，Spring 不会再兜一层，
     * 前端只能拿到一个与真实原因无关的 500，原始校验错误被彻底吞掉。
     */
    private String firstFieldMessage(BindingResult br) {
        FieldError fieldError = br.getFieldError();
        if (fieldError != null) {
            return fieldError.getField() + ": " + fieldError.getDefaultMessage();
        }
        ObjectError globalError = br.getGlobalError();
        if (globalError != null) {
            return globalError.getDefaultMessage();
        }
        return ErrorCode.PARAM_INVALID.getMsg();
    }

    /**
     * 取首条约束违规的文案。
     * <p>
     * 属性路径形如 {@code seckillVoucher.voucherId}（方法名.参数名），
     * 只取最后一段，前端看到的才是干净的字段名。
     */
    private String firstViolationMessage(ConstraintViolationException ex) {
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return ErrorCode.PARAM_INVALID.getMsg();
        }
        ConstraintViolation<?> violation = violations.iterator().next();
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        return field.isEmpty() ? violation.getMessage() : field + ": " + violation.getMessage();
    }
}
