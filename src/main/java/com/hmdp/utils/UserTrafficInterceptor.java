package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.USER_UV_KEY;

/**
 * UV 统计拦截器（order = 2）
 * <p>
 * 在用户已登录的前提下，对每个请求把 userId 写入当天的 HyperLogLog（uv:yyyyMMdd），
 * 后续即可用 {@code GET /user/uv} 统计任意时间范围内的独立访客数。
 * HyperLogLog 对同一个 userId 自动去重，因此同一用户当天多次访问只算 1 个 UV。
 */
@Slf4j
public class UserTrafficInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public UserTrafficInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 仅统计已登录用户（UserHolder 由 RefreshTokenInterceptor 填充）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return true;
        }
        // 2. 当天日期对应的 UV key：uv:yyyyMMdd
        String key = USER_UV_KEY + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try {
            // 3. 记录 UV：PFADD uv:yyyyMMdd userId（HyperLogLog 自动去重）
            stringRedisTemplate.opsForHyperLogLog().add(key, user.getId().toString());
        } catch (Exception e) {
            log.warn("UserTrafficInterceptor: 记录UV失败（忽略） | key={} | err={}", key, e.toString());
        }
        return true;
    }
}
