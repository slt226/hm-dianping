package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.UserCoupon;
import com.hmdp.mapper.UserCouponMapper;
import com.hmdp.utils.CouponConst;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 券的 Redis 索引维护（施工手册 Day 10）。
 * <p>
 * 这里只管两件事：<strong>把券挂进 ZSet</strong>、<strong>把券从 ZSet 摘掉</strong>。
 * 查询不在这里，因为查询要拼业务视图（面额、剩余小时），属于 Service 的活。
 * <p>
 * <strong>核心原则（与 Day 5 的库存、Day 7 的 {@code coupon:stock:} 一脉相承）：
 * Redis 是数据库的副本，不是真相。</strong>
 * 所以：
 * <ul>
 *   <li>所有写操作都包在 try-catch 里，Redis 挂了不能阻断领券/核销；</li>
 *   <li>启动时从数据库重建一遍，这样 Redis 重启、flush、或者某次写失败留下的空洞都能自愈；</li>
 *   <li>查询侧拿到 id 后必须回表，并且要容忍「ZSet 里有、库里已经不是未使用状态」的脏数据。</li>
 * </ul>
 * 少了最后这条，就会出现「提醒用户券快过期，用户打开发现早就用掉了」这种低级问题。
 */
@Slf4j
@Service
public class CouponIndexService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserCouponMapper userCouponMapper;

    /**
     * 事务提交后再挂索引，与 {@link #removeExpireAfterCommit} 对称。
     * <p>
     * 退券必须走这个版本：退券成功后券变回「未使用」，它重新变成一张会过期的券，
     * 理应回到临期索引里。而核销时是<strong>摘</strong>索引，退券时是<strong>挂</strong>，
     * 一摘一挂必须成对，否则退回来的券会从提醒列表里永久消失——
     * 不报错、不影响用券，只是用户再也收不到这张券的到期提醒。
     * <p>
     * 提交前挂的问题：如果后面还有别的券退失败导致整体回滚，券回到「已使用」，
     * 但索引里已经有它了，提醒就会为一张已经用掉的券发出去。
     */
    public void addExpireAfterCommit(Long userCouponId, LocalDateTime expireAt) {
        if (userCouponId == null || expireAt == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            addExpire(userCouponId, expireAt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                addExpire(userCouponId, expireAt);
            }
        });
    }

    /**
     * 把一张券挂进临期索引。
     * <p>
     * score 用 <strong>秒级</strong>时间戳，不是毫秒。理由有两个：
     * <ul>
     *   <li>毫秒时间戳约 1.7e12，double 的 53 位尾数在这种量级下仍然精确到个位，
     *       但一旦有人把「天」也乘成毫秒（86400000），很容易在区间边界上算错一位；</li>
     *   <li>券的有效期是「天」级别的，秒级精度足够，score 小一点排查时也好看。</li>
     * </ul>
     * 关键是<strong>全项目统一</strong>：写入用秒、查询也必须用秒。混用会让区间整体偏移 1000 倍，
     * 表现是「什么都查不出来」或者「什么都查出来」。
     */
    public void addExpire(Long userCouponId, LocalDateTime expireAt) {
        if (userCouponId == null || expireAt == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.COUPON_EXPIRE_ZSET,
                    String.valueOf(userCouponId),
                    toScore(expireAt));
        } catch (Exception e) {
            // 索引只是加速副本，写失败不影响正确性：查询侧会降级走 SQL
            log.error("临期索引写入失败 userCouponId={}, expireAt={}", userCouponId, expireAt, e);
        }
    }

    /**
     * 把一张券从临期索引摘掉。
     * <p>
     * 只在<strong>核销</strong>时调用。退券不动索引——券退回来还是可用的，
     * 它仍然会过期，仍然该被提醒。
     * <p>
     * 券过期后不需要主动删：查询的时间下界是「现在」，
     * 已过期的成员天然落在区间外。它们会以「僵尸成员」的形式留在 ZSet 里，
     * 由 {@link #purgeExpired()} 定期清掉——这就是 ZSet 版 TTL 的代价。
     */
    public void removeExpire(Long userCouponId) {
        if (userCouponId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet()
                    .remove(RedisConstants.COUPON_EXPIRE_ZSET, String.valueOf(userCouponId));
        } catch (Exception e) {
            log.error("临期索引删除失败 userCouponId={}", userCouponId, e);
        }
    }

    /**
     * 事务提交后再摘索引。
     * <p>
     * 为什么不能直接 {@link #removeExpire}：核销方法是一个事务，一张券摘了索引之后，
     * 同一单里第二张券校验失败会整体回滚——数据库里的券变回「未使用」，
     * 但 Redis 里那条索引已经没了，这张券再也不会出现在临期提醒里。
     * <p>
     * Redis 不是事务资源，Spring 不会帮你回滚它。唯一可靠的做法是
     * <strong>等提交成功再动缓存</strong>：提交前动，回滚就丢数据；
     * 提交后动，最坏情况是「提交成功但删索引失败」，那只是多一条僵尸成员，
     * 查询侧会过滤掉（券已不是未使用状态），比丢数据安全得多。
     * <p>
     * 没有事务时（例如被非事务方法直接调用）同步执行，行为一致。
     */
    public void removeExpireAfterCommit(Long userCouponId) {
        if (userCouponId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            removeExpire(userCouponId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                removeExpire(userCouponId);
            }
        });
    }

    /**
     * 记录一条发放明细，供 {@code ZRANGEBYLEX} 按周期区间扫描。
     * <p>
     * 三个部分用 {@code #} 分隔而不是 {@code :}，因为 period 里已经有 {@code -}，
     * 而 {@code #} 的 ASCII 码小于所有数字和字母，切片时不会和内容混淆。
     * <p>
     * 顺序是 period 在前 —— 这是整个设计的关键：字典序 = 时间序，
     * 才能用 {@code [2026-01# (2026-04#} 一次取出一个季度。
     * 如果把 userId 放前面，索引就只能按用户扫，跨用户的区间统计彻底失效。
     */
    public void addGrantPeriod(String period, Long userId, Long templateId) {
        if (period == null || userId == null || templateId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForZSet().add(
                    RedisConstants.COUPON_GRANT_ZSET,
                    period + "#" + userId + "#" + templateId,
                    0D);
        } catch (Exception e) {
            log.error("发放索引写入失败 period={}, userId={}, templateId={}",
                    period, userId, templateId, e);
        }
    }

    /**
     * 清掉已经过期、但还赖在 ZSet 里的成员。
     * <p>
     * 由定时任务调用（每天一次即可）。不做的话 ZSet 会无限增长，
     * 而 Redis 的过期时间只能整 key 设置，没法「只让某几个成员过期」。
     */
    public long purgeExpired() {
        try {
            Long n = stringRedisTemplate.opsForZSet().removeRangeByScore(
                    RedisConstants.COUPON_EXPIRE_ZSET, 0D, (double) nowSeconds());
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.error("临期索引清理失败", e);
            return 0L;
        }
    }

    /**
     * 启动时从数据库重建临期索引。
     * <p>
     * 为什么必须有这一步：Redis 是内存库，重启、被运维 flush、或者某次 ZADD 静默失败，
     * 索引就和数据库脱节了。而索引缺失的表现是「运营扫不到该提醒的券」——不报错、不影响下单，
     * 只影响那一批提醒，等于要等业务方发现「最近怎么没人收到提醒」才暴露。
     * <p>
     * 只重建<strong>未来 N 天内到期且未使用</strong>的券：重建的目的就是「临期提醒」，
     * 三年后才过期的券现在不需要出现在这个索引里，扫全表纯属浪费启动时间。
     * <p>
     * 先删后建（同一个 key 上 DEL + ZADD）在单实例启动时是安全的；
     * 多实例滚动发布时，后启动的实例会把先启动实例期间新写入的成员覆盖掉——
     * 但这没关系，因为覆盖的只是「未来 N 天内到期的券」，而这些券本来就会被重新扫进来。
     */
    @PostConstruct
    public void rebuildExpireIndex() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime until = now.plusDays(RedisConstants.COUPON_EXPIRE_REBUILD_DAYS);

            List<UserCoupon> list = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                    .select("id", "expire_time")
                    .eq("status", CouponConst.UC_UNUSED)
                    .gt("expire_time", now)
                    .le("expire_time", until)
                    .orderByAsc("expire_time")
                    .last("LIMIT 5000"));

            stringRedisTemplate.delete(RedisConstants.COUPON_EXPIRE_ZSET);
            if (list.isEmpty()) {
                log.info("临期索引重建完成：未来 {} 天内没有待到期券", RedisConstants.COUPON_EXPIRE_REBUILD_DAYS);
                return;
            }
            for (UserCoupon uc : list) {
                stringRedisTemplate.opsForZSet().add(
                        RedisConstants.COUPON_EXPIRE_ZSET,
                        String.valueOf(uc.getId()),
                        toScore(uc.getExpireTime()));
            }
            log.info("临期索引重建完成：{} 条（未来 {} 天内到期）",
                    list.size(), RedisConstants.COUPON_EXPIRE_REBUILD_DAYS);
        } catch (Exception e) {
            // 重建失败不能阻止应用启动，索引缺失只是降级（查询会走 SQL 兜底）
            log.error("临期索引重建失败，查询将降级走数据库", e);
        }
    }

    /** LocalDateTime → epoch 秒。全项目统一的 score 基准，写入和查询都必须用它 */
    public static double toScore(LocalDateTime t) {
        return (double) t.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /** 当前 epoch 秒，整数 */
    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
