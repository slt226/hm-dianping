package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器（第二个执行，order = 1）
 * <p>
 * 只负责"登录校验"这一个职责：判断当前请求的用户是否已登录。
 * 用户信息由 RefreshTokenInterceptor 从 Redis 查出并存入 ThreadLocal，
 * 这里直接从 UserHolder 取，未登录则返回 401。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断 ThreadLocal 中是否有用户（由 RefreshTokenInterceptor 存入）
        if (UserHolder.getUser() == null) {
            // 2.没有用户说明未登录，拦截，返回 401
            response.setStatus(401);
            return false;
        }
        // 3.已登录，放行
        return true;
    }
}
