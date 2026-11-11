package com.hmdp.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.monitor.QueueProbe;
import com.hmdp.service.ISeckillDeadLetterService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 秒杀对账任务（秒杀 P0 · 04）：系统的最后一道自愈机制。
 * <p>
 * 它不看消息，只看<strong>终点状态</strong>：Redis 闸门和 DB 账本差多少，差的补回来。
 * 账本口径（全课的地基）：
 * <pre>
 *   DB_stock    = S0 - 已落库订单数
 *   Redis_stock = S0 - 已落库订单数 - 在途预扣数 P
 *   ⇒ Redis_stock = DB_stock - P，且 P ≥ 0
 * </pre>
 * 所以 <strong>Redis &gt; DB 一定是坏账</strong>（P 为负），而 Redis &lt; DB 可能只是有在途。
 * 代码里四条防护，缺一条都会造成"对账脚本自己制造超卖"：
 * <ol>
 *   <li><strong>在途门禁</strong>：主队列 / 死信队列还有消息、或该券还有待补偿死信 → 本轮不修；</li>
 *   <li><strong>两轮确认</strong>：{@code queueDeclarePassive} 只报 ready、不报 unacked，
 *       连续两轮都安静才能确认 P 真的为 0（消费端重试窗口约 3 秒，远小于 1 分钟扫描间隔）；</li>
 *   <li><strong>CAS 写入</strong>：Lua 里比较"我读到的值"再写；读到 98、期间被别人扣成 97 时写入失败，
 *       下一轮重算——这是防止"把真实扣减回滚掉"的关键；</li>
 *   <li><strong>多实例互斥</strong>：Redis 锁 + TTL，避免两个实例对同一张券重复修。</li>
 * </ol>
 * 本类不加 {@code @Transactional}：改的是 Redis，DB 只读，事务无从施加。
 */
@Slf4j
@Component
public class SeckillReconcileTask {

    private static final String MAIN_QUEUE = "seckill.queue";
    private static final String DLQ = "seckill.dlq";

    /** 对账锁 TTL：足够跑完一张券，又不至于实例崩溃后永久占锁 */
    private static final long LOCK_TTL_SECONDS = 120L;

    /** 结束后的收尾窗口：活动结束后 24 小时内仍做终态对账 */
    private static final long GRACE_HOURS = 24L;

    /**
     * 已抢集合的全量差集上限。
     * <p>
     * SMEMBERS 会把所有 member 拉进堆内存，热门券十万级时这一条命令就能打出一次 Full GC。
     * 超限时跳过并告警：漏修不会造成超卖（DB 的 uk_uid_vid 是最后一道闸），
     * 但把对账任务自己搞崩会造成"所有券都不再收敛"。
     */
    private static final long USER_SET_DIFF_LIMIT = 5000L;

    /** 每轮最多重试补偿多少条待处理死信 */
    private static final int SETTLE_BATCH = 200;

    /** 比较并写入：值没变才写，杜绝"读-比-写"之间被别人扣走库存 */
    private static final RedisScript<Long> STOCK_CAS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2]) return 1 else return 0 end",
            Long.class);

    /** 只删自己加的锁：value 不匹配就不动，避免误解锁别人 */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private ISeckillDeadLetterService deadLetterService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private QueueProbe queueProbe;

    /**
     * 券 → 上一轮是否"安静"。对账要求连续两轮安静才动手：
     * 第一轮只做观察，避免把"消费者刚拿到手、还没 ack"的消息当成丢单。
     * <p>
     * 用内存 Map 而不是 Redis：这个状态是"本实例的观察结论"，丢了最多多等一轮，
     * 不影响正确性，没必要为它引入一份需要同步的远程状态。
     */
    private final Map<Long, Boolean> quietRounds = new ConcurrentHashMap<>();

    /**
     * 每分钟整分执行。
     * <p>
     * 为什么是 cron 而不是 fixedDelay：{@code @EnableScheduling} 默认只有 1 个线程，
     * 任务天然串行、不会自己重叠；整分触发让日志时间戳对齐，便于按分钟 grep 复盘。
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void scheduledReconcile() {
        try {
            reconcileAll();
        } catch (Exception e) {
            // 定时任务必须兜住所有异常：抛出去会被线程池静默吞掉，之后再也看不到失败
            log.error("【对账】本轮整体失败（不影响下一轮）", e);
        }
    }

    /**
     * 一轮对账：先自愈死信，再逐张券核对。
     *
     * @return 本轮被修正的券数（库存或用户集合任一被修正即计 1）
     */
    public int reconcileAll() {
        // 阶段一：把"补偿失败的死信"再补一次。补不掉的会一直留在 status=0，
        // 下面的在途门禁会因此拒绝修这张券的库存——这正是两条腿必须一起走的原因。
        int settled = deadLetterService.settlePending(SETTLE_BATCH);

        LocalDateTime now = LocalDateTime.now();
        // 只扫"正在进行 + 刚结束 24 小时"的券：
        // 未开始的券 Redis 库存就等于 DB 值，没有必要核对；
        // 结束太久的券没人买了，修了只是改个数字，留给归档任务。
        List<SeckillVoucher> vouchers = seckillVoucherService.list(
                new QueryWrapper<SeckillVoucher>()
                        .le("begin_time", now)
                        .ge("end_time", now.minusHours(GRACE_HOURS)));
        if (vouchers == null || vouchers.isEmpty()) {
            log.debug("【对账】没有处于活动窗口的券，跳过");
            return 0;
        }

        int fixed = 0;
        for (SeckillVoucher voucher : vouchers) {
            if (reconcileOne(voucher.getVoucherId()) > 0) {
                fixed++;
            }
        }
        if (settled > 0 || fixed > 0) {
            log.warn("【对账】本轮汇总 | 待补偿死信成功 {} 条，修正券 {} / {}", settled, fixed, vouchers.size());
        }
        return fixed;
    }

    /**
     * 对单张券：门禁 → 两轮确认 → 互斥 → 修库存 → 修已抢集合。
     *
     * @return 本张券的修正动作数（0 表示无需修正或本轮不允许修正）
     */
    public int reconcileOne(Long voucherId) {
        if (voucherId == null) {
            return 0;
        }

        // ① 在途门禁：账本只有在"没人动"的时候才是可比的
        //    探针逻辑在 QueueProbe 里（05 抽取）：告警任务读的是同一条事实，不能各写一份
        QueueProbe.Stat main = queueProbe.stat(MAIN_QUEUE);
        QueueProbe.Stat dlq = queueProbe.stat(DLQ);
        long pendingDeadLetters = deadLetterService.countPending(voucherId);
        boolean quiet = main.isOk() && dlq.isOk()
                && main.getReady() == 0 && dlq.getReady() == 0
                && pendingDeadLetters == 0;
        if (!quiet) {
            quietRounds.put(voucherId, false);
            // 把 consumers 一起打出来：ready>0 且 consumers=0 说明"有人在积压、没人在消费"，
            // 这是比"有在途"严重得多的事故，看到这行日志必须立刻查消费者存活
            // （05 起这类情况还会被 SeckillQueueAlertTask 升级成独立告警）
            log.info("【对账】存在在途，本轮跳过 | voucherId={}, mainReady={}(consumers={}), dlqReady={}(consumers={}), pendingDeadLetter={}",
                    voucherId, main.getReady(), main.getConsumers(),
                    dlq.getReady(), dlq.getConsumers(), pendingDeadLetters);
            return 0;
        }

        // ② 两轮确认：ready=0 也可能是"消息正被消费者拿在手里还没 ack"，那同样属于在途
        if (!Boolean.TRUE.equals(quietRounds.put(voucherId, true))) {
            log.info("【对账】安静窗口第 1 轮（仅观察），下一轮执行修复 | voucherId={}", voucherId);
            return 0;
        }

        // ③ 多实例互斥
        String lockKey = RedisConstants.SECKILL_RECONCILE_LOCK_KEY + voucherId;
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("【对账】另一实例正在核对，跳过 | voucherId={}", voucherId);
            return 0;
        }

        try {
            return reconcileStock(voucherId) + reconcileUsers(voucherId);
        } finally {
            unlock(lockKey, token);
        }
    }

    /**
     * 修正库存：目标值是 DB 账本，写入用 CAS。
     * <p>
     * 两个方向都要修，但含义完全不同：
     * <ul>
     *   <li>{@code Redis > DB} —— 闸门虚高，活动会卖出 DB 没有的货（A 类坏账），必须压回来；</li>
     *   <li>{@code Redis < DB} —— 闸门偏低，明明有货却提示售罄（B 类坏账），必须抬上去，否则真金白银少卖。</li>
     * </ul>
     *
     * @return 1 修正成功，0 无需修正或放弃
     */
    private int reconcileStock(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getStock() == null) {
            return 0;
        }
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String redisRaw = stringRedisTemplate.opsForValue().get(stockKey);
        if (redisRaw == null) {
            // 键不存在 ≠ 库存为 0：多半是没预热或被 flush 了。
            // 这里绝不能 SET 一个值回去——会把"整个闸门清零"当成正常状态固化下来。
            log.warn("【对账】Redis 库存键不存在（未预热或被清空），本轮不修 | voucherId={}, dbStock={}",
                    voucherId, voucher.getStock());
            return 0;
        }
        int redisStock;
        try {
            redisStock = Integer.parseInt(redisRaw.trim());
        } catch (NumberFormatException e) {
            log.error("【对账】Redis 库存不是数字，跳过 | key={}, value={}", stockKey, redisRaw);
            return 0;
        }
        int dbStock = voucher.getStock();
        if (redisStock == dbStock) {
            return 0;
        }

        // 必须拿 redisRaw 原值去比较，不能拿 parseInt 后的结果：
        // "098" 解析成 98 再回写，Lua 的字符串比较就对不上，CAS 永远失败。
        Long cas = stringRedisTemplate.execute(STOCK_CAS_SCRIPT,
                Collections.singletonList(stockKey), redisRaw, String.valueOf(Math.max(dbStock, 0)));
        if (cas == null || cas == 0L) {
            // 典型场景：读到 98，判断"要和 DB 对齐"，但这 1 毫秒里有人抢走一张、把它扣成了 97。
            // 如果直接 SET 98，就等于把这次真实扣减抹掉 → 对账脚本亲手制造 1 张超卖。
            log.info("【对账】库存已被并发修改，本轮放弃（下一轮重算）| voucherId={}, seen={}, db={}",
                    voucherId, redisStock, dbStock);
            return 0;
        }
        log.warn("【对账】库存已修正 | voucherId={}, redis {} -> {}, db={} ({})",
                voucherId, redisStock, dbStock, dbStock,
                redisStock > dbStock ? "闸门虚高，压回账本" : "闸门偏低，补回账本");
        return 1;
    }

    /**
     * 修正"已抢购集合"：以 DB 订单为唯一事实来源。
     * <p>
     * 两类坏账方向相反，危害也相反：
     * <ul>
     *   <li><strong>补缺</strong>（C 类）：DB 有单、Redis 没记 → 这个人下次还能抢，
     *       一定会撞 {@code uk_uid_vid} 变成"下单失败但预扣没退"的幽灵单；</li>
     *   <li><strong>清幽灵</strong>（D 类）：Redis 记了、DB 没单 → 这个账号永远"每人限抢一张"，
     *       相当于被永久封禁。注意它和"A 类超卖"是两件事：集合多了人不代表库存错了。</li>
     * </ul>
     * 调用方已经过了在途门禁，所以这里不必再判 P。
     *
     * @return 修正动作数（补缺 / 清幽灵各算 1）
     */
    private int reconcileUsers(Long voucherId) {
        String userKey = RedisConstants.SECKILL_USER_KEY + voucherId;
        Long cardinality = stringRedisTemplate.opsForSet().size(userKey);
        long redisSize = cardinality == null ? 0L : cardinality;
        if (redisSize > USER_SET_DIFF_LIMIT) {
            log.warn("【对账】已抢集合过大，跳过全量差集（避免 SMEMBERS 打爆内存）| voucherId={}, size={}, limit={}",
                    voucherId, redisSize, USER_SET_DIFF_LIMIT);
            return 0;
        }
        Set<String> redisUsers = stringRedisTemplate.opsForSet().members(userKey);
        if (redisUsers == null) {
            redisUsers = Collections.emptySet();
        }

        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                new QueryWrapper<VoucherOrder>().select("user_id").eq("voucher_id", voucherId));
        Set<String> dbUsers = orders == null ? Collections.emptySet()
                : orders.stream().map(o -> String.valueOf(o.getUserId())).collect(Collectors.toSet());

        int fixed = 0;

        // 补缺：DB 有单但 Redis 漏记
        Set<String> missing = new HashSet<>(dbUsers);
        missing.removeAll(redisUsers);
        if (!missing.isEmpty()) {
            stringRedisTemplate.opsForSet().add(userKey, missing.toArray(new String[0]));
            log.warn("【对账】已抢集合补回 {} 人（DB 有单、Redis 缺失）| voucherId={}, users={}",
                    missing.size(), voucherId, missing);
            fixed++;
        }

        // 清幽灵：Redis 有但 DB 无单
        Set<String> ghosts = new HashSet<>(redisUsers);
        ghosts.removeAll(dbUsers);
        if (!ghosts.isEmpty()) {
            stringRedisTemplate.opsForSet().remove(userKey, ghosts.toArray());
            log.warn("【对账】已抢集合清理 {} 个幽灵用户（Redis 有、DB 无单）| voucherId={}, users={}",
                    ghosts.size(), voucherId, ghosts);
            fixed++;
        }
        return fixed;
    }

    /**
     * 读取队列状态的能力已抽到 {@link QueueProbe}（秒杀 P0 · 05）。
     * <p>
     * 抽走的理由不是"代码复用"这么空：{@code SeckillQueueAlertTask} 每 30 秒要读同一份数据，
     * 若各写一份 {@code queueDeclarePassive}，"对账看到的在途"和"告警看到的积压"
     * 就可能因为实现漂移而互相矛盾 —— 而排查事故时，最忌讳两个监控说两套话。
     */
    private void unlock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 释放失败不影响正确性：TTL 到期自动消失
            log.warn("【对账】释放对账锁失败（等 TTL 过期）| key={}", lockKey, e);
        }
    }
}
