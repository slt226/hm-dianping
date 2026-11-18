package com.hmdp.utils;

import com.hmdp.dto.ErrorCode;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 拦截器里回写 JSON 错误体的工具。
 * <p>
 * 为什么需要它：{@link LoginInterceptor} / {@link AdminInterceptor} 只做了
 * {@code setStatus(401/403); return false;}，<strong>body 是空的</strong>。
 * 前端拿到一个空响应，既不知道是"没登录"还是"权限不足"，也没法按 code 分支处理。
 * <p>
 * 为什么手拼字符串而不是用 Jackson：这两个拦截器是在 {@code MvcConfig} 里
 * {@code new} 出来的普通对象，不是 Spring Bean，注入不了 {@code ObjectMapper}。
 * 为一个固定格式的三字段 JSON 去搞静态单例属于过度设计。
 * <p>
 * <strong>两个必须记住的细节</strong>：
 * <ol>
 *   <li>{@code setContentType} 里的 {@code charset=UTF-8} 不能省。少了它，
 *       {@code getWriter()} 用的是容器默认编码，中文提示会变成乱码。</li>
 *   <li>文案取自枚举，别在这里写中文常量 —— 否则同一个"请先登录"会有两份定义，
 *       改文案时必漏一处。</li>
 * </ol>
 * 注意这里直接给 401 / 403 而不是走 {@link com.hmdp.config.ApiStatus}：
 * 拦截器阶段请求<strong>根本没进 Spring MVC</strong>，不属于"业务失败"，
 * 鉴权失败必须在 HTTP 层就表达出来，浏览器和网关才知道该跳登录页还是拦掉。
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    /** 未登录：HTTP 401 + code 4101 */
    public static void unauthorized(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    /** 已登录但无权限：HTTP 403 + code 4103 */
    public static void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    private static void write(HttpServletResponse response, int status, ErrorCode errorCode) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + errorCode.getCode()
                + ",\"success\":false"
                + ",\"errorMsg\":\"" + errorCode.getMsg() + "\"}");
    }
}
