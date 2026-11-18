package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理端鉴权拦截器（order = 2），挂在建券、成长值手动入账、秒杀运维入口上。
 * <p>
 * 与 {@link LoginInterceptor} 的分工：后者只回答"有没有登录"，
 * 这里回答"是不是管理员"。两个条件都要满足，所以执行顺序不能反。
 * <p>
 * 403 也必须带 body（code 4103）：只有状态码时前端只能弹"请求失败"，
 * 分不清是登录态失效还是点错了入口。
 */
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if (user == null || !AdminConst.ADMIN_ID.equals(user.getId())) {
            ErrorResponses.forbidden(response);
            return false;
        }
        return true;
    }
}
