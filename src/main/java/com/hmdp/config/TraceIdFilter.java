package com.hmdp.config;

import com.hmdp.utils.TraceIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 全链路 traceId（任务 6）：把一次请求的所有日志串成一条线。
 * <p>
 * 为什么用 {@link OncePerRequestFilter} 而不是拦截器（HandlerInterceptor）：
 * <ol>
 *   <li>拦截器只能覆盖"进入 Controller"之后的请求；<strong>静态资源、404、拦截器自己返回的 401</strong>
 *       都到不了拦截器，而这些恰恰是排查时最想看的；</li>
 *   <li>过滤器在 DispatcherServlet 之前执行，连 Spring MVC 的异常解析都在它的覆盖范围内。</li>
 * </ol>
 * <p>
 * {@code HIGHEST_PRECEDENCE} 保证它在所有过滤器中最先执行 —— 否则前面别的过滤器打的日志没有 traceId。
 * <p>
 * {@code finally} 里的清除不能省：Servlet 容器是线程池，线程会被下一个请求复用，
 * 不清就会出现"请求 A 的日志带着请求 B 的 traceId"，和 ThreadLocal 是同一个坑。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = TraceIds.resolve(request.getHeader(TraceIds.HEADER));
        TraceIds.put(traceId);
        // 回写响应头：前端报错截图时能看到 id，前端同学也能拿它来问"这一单后端发生了什么"
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIds.clear();
        }
    }
}
