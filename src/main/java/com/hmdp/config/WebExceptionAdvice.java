package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理器：只管「预期外的故障」。
 * <p>
 * 业务异常（BizException）和唯一索引冲突在 {@link BizExceptionAdvice} 里，
 * 那边打 warn 不打堆栈；这里打 error 带完整堆栈。
 * 分开的理由见 BizExceptionAdvice 的类注释。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        // 完整堆栈只进日志，不返回给前端——里面会有 SQL、类名、内网地址
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
