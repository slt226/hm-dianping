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
    // 秒杀券元数据（仅时间窗）缓存前缀，完整 key = seckill:meta:{voucherId}
    public static final String SECKILL_META_KEY = "seckill:meta:";
    // 30 分钟：券的时间窗只在建券时确定、活动前不改，最坏 30 分钟的不一致业务可接受
    public static final Long SECKILL_META_TTL = 30L;

    /**
     * 死信补偿执行权标记（04）：{@code seckill:dlq:done:{orderId}}。
     * <p>
     * 存在 ⇒ 这条死信已经成功回补过，重复投递/并发补偿都不要再动库存。
     * 补偿<strong>失败</strong>时会被主动删除，让对账任务下一轮还能再试。
     */
    public static final String SECKILL_DLQ_DONE_KEY = "seckill:dlq:done:";

    /**
     * 对账互斥锁（04）：{@code lock:seckill:reconcile:{voucherId}}。
     * <p>
     * 单机时 @Scheduled 默认只有一个线程，任务天然串行；多实例部署时两个实例会同时
     * 读到同一份"坏账"并各自修一次，虽然 CAS 让结果不至于错乱，但日志会互相污染、白跑一遍。
     */
    public static final String SECKILL_RECONCILE_LOCK_KEY = "lock:seckill:reconcile:";

    /**
     * 人工重放互斥锁（05）：{@code lock:seckill:dlq:replay:{id}}。
     * <p>
     * 挡的是"运维连点两次"和"两个运维同时点"。真正的重复补偿由 doneKey 兜底，
     * 这把锁只保证 {@code retry_count} 不会因为手抖被加两次 —— 而这个数字是复盘时唯一的依据。
     */
    public static final String SECKILL_DLQ_REPLAY_LOCK_KEY = "lock:seckill:dlq:replay:";

    /**
     * 管理端独立令牌（任务 6）：{@code admin:token:{token}}，值任意，存在即视为有效。
     * <p>
     * 为什么不复用用户登录态：{@code /seckill/ops/**} 里的重放会直接改库存，
     * 一旦"能变成 userId=1"就能调运维接口，等于把写库存的权限挂在用户体系上。
     * 独立前缀 + 独立 TTL 之后，运维令牌泄露的爆炸半径与用户体系完全隔离。
     */
    public static final String ADMIN_TOKEN_KEY = "admin:token:";

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
    // 券
    public static final String COUPON_TEMPLATE_KEY = "coupon:template:";   // + templateId
    public static final String COUPON_STOCK_KEY = "coupon:stock:";         // + templateId
    public static final String COUPON_USER_COUNT_KEY = "coupon:count:";   // + templateId + ":" + userId
    public static final Long COUPON_TEMPLATE_TTL = 30L;                      // 分钟

    /**
     * 临期券索引（Day 10）：全局 ZSet，score = 过期时间（epoch 秒），member = 用户券 id。
     * <p>
     * 为什么是「全局一个 key」而不是「每个用户一个 key」：
     * 用户手上的券通常只有几张到几十张，按 user_id 查走
     * {@code idx_user_status_expire} 索引的 SQL 已经足够快，再套一层 ZSet 纯属增加不一致来源。
     * 真正扛不住的是运营侧「扫出未来 24 小时要过期的券」这种<strong>跨用户</strong>查询，
     * {@code WHERE expire_time BETWEEN ? AND ? AND status = 0} 会退化成大范围扫描，
     * 这才需要 ZSet 的有序性。
     */
    public static final String COUPON_EXPIRE_ZSET = "coupon:expire:soon";

    /**
     * 发放明细索引（Day 10）：全局 ZSet，<strong>score 全部为 0</strong>，
     * member = {@code {period}#{userId}#{templateId}}，例如 {@code 2026-11#1016#5}。
     * <p>
     * score 全等不是偷懒，是 {@code ZRANGEBYLEX} 的<strong>前置条件</strong>：
     * 该命令只在 score 相同时才按 member 的字典序排序；只要有任何一个成员 score 不同，
     * 字典序就只在这个 score 分组内成立，跨组的结果是乱的。
     * <p>
     * member 把 period 放在最前面且定长 7 位（{@code yyyy-MM}），
     * 字典序才等于时间序，{@code [2026-01# (2026-04#} 才能取到整个一季度。
     */
    public static final String COUPON_GRANT_ZSET = "coupon:grant:index";

    /** 临期索引重建时向前看的天数：只索引未来 30 天内到期的券，避免启动时扫全表 */
    public static final int COUPON_EXPIRE_REBUILD_DAYS = 30;
}
