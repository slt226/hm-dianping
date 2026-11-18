package com.hmdp.config;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理器：只管「预期外的故障」。
 * <p>
 * 业务异常（BizException）、唯一索引冲突、参数校验失败都在 {@link BizExceptionAdvice} 里，
 * 那边打 warn 不打堆栈；这里打 error 带完整堆栈。
 * 分开的理由见 BizExceptionAdvice 的类注释。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException e) {
        // 完整堆栈只进日志，不返回给前端——里面会有 SQL、类名、内网地址
        log.error(e.toString(), e);
        // 必须显式给 5000：走 fail("服务器异常") 会归口到 BIZ_GENERAL(4000)，
        // 监控按 code like '5%' 筛真实故障时就一条都筛不出来，兜底等于白兜。
        // 状态码由 ApiStatus 统一决定，5xxx 映射 500，这样 Prometheus 的 5xx 指标才有数。
        return ResponseEntity.status(ApiStatus.of(ErrorCode.INTERNAL_ERROR))
                .body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
