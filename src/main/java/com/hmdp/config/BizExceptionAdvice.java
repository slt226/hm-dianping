package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
     */
    @ExceptionHandler(BizException.class)
    public Result handleBizException(BizException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 唯一索引冲突的兜底。
     * <p>
     * 领券那条链路里已经 try-catch 了自己处理（要补偿库存和计数），
     * 这里兜的是没显式 catch 的地方——比如会员月度发放。
     * 用户看到的应该是「别重复提交」，不是「服务器异常」。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突：{}", e.getMostSpecificCause().getMessage());
        return Result.fail("请勿重复提交");
    }
}
