package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;        // 验证码有效期（分钟）
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;    // 登录 token 有效期（秒，=10小时）

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;       // 商品类型列表缓存30分钟 + 随机值防雪崩

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_USER_KEY = "seckill:user:";  // 秒杀一人一单用户Set前缀
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String USER_UV_KEY = "uv:";          // UV 统计(HyperLogLog)按天 key 前缀，完整 key = uv:yyyyMMdd

    public static final String FOLLOWS_KEY = "follows:";  // 用户关注集合 key 前缀
    public static final String FANS_KEY = "fans:";        // 用户粉丝集合 key 前缀

    public static final String ICR_KEY_PREFIX = "icr:";   // Redis全局ID生成器的自增序列 key 前缀
    public static final int COUNT_BITS = 32;               // 序列号位数 = 2^32 （每个业务每天最多42亿）
    public static final long ID_WORKER_BEGIN_TIMESTAMP = 1640995200L; // 基准秒级时间戳：2022-01-01 00:00:00（UTC+0）
}
