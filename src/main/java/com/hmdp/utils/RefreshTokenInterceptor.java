package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 刷新 Token 拦截器（第一个执行，order = 0）
 * <p>
 * 拦截所有请求（/**），职责只有一个：只要请求携带了有效的 token，
 * 就刷新其有效期并保存用户到 ThreadLocal，保证"用户一直在操作就不会掉线"。
 * <p>
 * 无论是否登录都放行 —— 真正的登录校验交给 LoginInterceptor 处理。
 */
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的 token
        String token = request.getHeader("authorization");
        // 2.没带 token 直接放行（不刷新，也无需登录校验交给下一个拦截器处理）
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;
        String json;
        try {
            // 3.基于 token 从 Redis 查询用户（Redis不可用时降级：当作未登录处理，不阻塞公开接口）
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("RefreshTokenInterceptor: Redis不可用，跳过登录态刷新 | path={} | err={}",
                    request.getRequestURI(), e.toString());
            return true;
        }
        // 4.token 无效或已过期：放行，由 LoginInterceptor 决定是否 401
        if (StrUtil.isBlank(json)) {
            return true;
        }
        // 5.查到用户：转为 UserDTO 存入 ThreadLocal，供后续业务使用
        UserDTO user;
        try {
            user = JSONUtil.toBean(json, UserDTO.class);
        } catch (Exception e) {
            log.warn("RefreshTokenInterceptor: 用户数据解析失败 | token={}", token);
            return true;
        }
        UserHolder.saveUser(user);
        // 6.刷新 token 有效期：只要用户在活跃，登录态就一直续期，不会被踢下线
        try {
            stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("RefreshTokenInterceptor: Redis续期失败（忽略） | key={} | err={}", key, e.toString());
        }
        // 7.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清理 ThreadLocal，防止内存泄漏
        UserHolder.removeUser();
    }
}
