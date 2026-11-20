package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 管理端鉴权拦截器（order = 2），挂在建券、成长值手动入账、秒杀运维入口上。
 * <p>
 * 与 {@link LoginInterceptor} 的分工：后者只回答"有没有登录"，这里回答"是不是管理员"。
 * <p>
 * <strong>为什么不再用 {@code userId == 1} 白名单（任务 6 加固）</strong>：
 * {@code /seckill/ops/**} 里的死信重放会直接改 Redis 库存，把"写库存"的权限绑在用户体系上，
 * 意味着任何能让自己的 id 变成 1 的路径（历史脏数据、登录逻辑 bug、内部接口越权）都能调它。
 * 现在改成独立的运维令牌 {@code admin:token:{token}}：<br>
 * ① 与用户登录态完全隔离，用户 token 再怎么样也换不出运维权限；<br>
 * ② 令牌可单独吊销（DEL 那个 key）、单独设 TTL，爆炸半径可控。
 * <p>
 * 请求头用 {@code X-Admin-Token} 而不是 {@code authorization}：和用户 token 用两个头，
 * 好处是代理/网关的日志里一眼能看出这是管理端调用。
 * <p>
 * 403 也必须带 body（code 4103）：只有状态码时前端只能弹"请求失败"，
 * 分不清是登录态失效还是点错了入口。
 */
public class AdminInterceptor implements HandlerInterceptor {

    /** 管理端令牌请求头 */
    public static final String TOKEN_HEADER = "X-Admin-Token";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 这个类是 {@code new} 出来的、不是 Spring Bean，所以依赖必须显式传入
     * （与 {@link RefreshTokenInterceptor} 的做法一致，见 MvcConfig）。
     */
    public AdminInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader(TOKEN_HEADER);
        // 空判断放在查 Redis 之前：绝大多数非法请求连一次 Redis 都不该消耗
        if (token == null || token.trim().isEmpty()) {
            ErrorResponses.forbidden(response);
            return false;
        }
        Boolean valid = stringRedisTemplate.hasKey(RedisConstants.ADMIN_TOKEN_KEY + token.trim());
        if (!Boolean.TRUE.equals(valid)) {
            ErrorResponses.forbidden(response);
            return false;
        }
        return true;
    }
}
