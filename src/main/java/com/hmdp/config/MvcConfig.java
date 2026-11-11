package com.hmdp.config;

import com.hmdp.utils.AdminInterceptor;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserTrafficInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Spring MVC 配置
 * <p>
 * 双拦截器方案：
 * 1. RefreshTokenInterceptor（order=0）：拦截所有请求，携带有效 token 就刷新登录态，
 *    解决"用户一直活跃却因登录态过期被踢下线"的问题；
 * 2. LoginInterceptor（order=1）：只对需要登录的接口做登录校验，未登录返回 401。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1.刷新 token 拦截器：拦截所有请求，order 最小，最先执行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // 2.登录校验拦截器：排除无需登录的公开接口，order 其次
        registry.addInterceptor(new LoginInterceptor())
                // 显式声明拦截范围：不写的话 includePatterns 是空数组，
                // 在部分 Spring 版本下 excludePathPatterns 的匹配行为不稳定
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 用户登录相关（公开）
                        "/user/code",
                        "/user/login",
                        // 商铺浏览相关（公开，首页和详情页都需要）
                        "/shop/**",
                        "/shop-type/**",
                        // 优惠券浏览（公开，查看店铺优惠券）
                        "/voucher/list/**",
                        // 券模板浏览（公开，店铺详情页与券中心不登录也能看，
                        // 领券接口 /coupon/receive 不在此列，Day 3 那条必须登录）
                        "/coupon/shop/**",
                        "/coupon/center",
                        // 建券接口（管理端）：施工手册 Day 19 才补权限，
                        // Spring Boot 的 404 会内部转发到 /error，不排除的话
                        // "路径不存在"会被 LoginInterceptor 伪装成 401，排查时极易被带偏
                        "/error",
                        // 博客热门列表（公开，首页展示）
                        "/blog/hot"
                )
                .order(1);
        registry.addInterceptor(new AdminInterceptor())
                .addPathPatterns("/coupon/template", "/member/growth/earn")
                // 秒杀运维入口（05）：重放会直接改库存，必须只有管理员能调
                .addPathPatterns("/seckill/ops/**")
                .order(2);


        // 3.UV 统计拦截器：拦截所有请求，对已登录用户记录当日独立访客（HyperLogLog），order 最后
        registry.addInterceptor(new UserTrafficInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(3);
    }

}
