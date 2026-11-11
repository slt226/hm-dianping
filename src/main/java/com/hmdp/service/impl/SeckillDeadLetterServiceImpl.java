package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.DeadLetterReplayVO;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillDeadLetter;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.SeckillDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillDeadLetterService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀死信记录服务实现（秒杀 P0 · 04）。
 * <p>
 * 三条铁律，代码里每一处都对应它们：
 * <ol>
 *   <li><strong>凭据优先</strong>：先落库再补偿；落库失败也继续补偿（库存正确性 &gt; 留证据）；</li>
 *   <li><strong>绝不重复回补</strong>：先查 DB 订单是否存在（误判死信），再用 Redis SETNX 抢执行权；</li>
 *   <li><strong>失败要能重试</strong>：补偿失败时删除 SETNX 标记，让下一轮对账还能再试。</li>
 * </ol>
 * 本类<strong>不能加 {@code @Transactional}</strong>：补偿只有 Redis 写、没有 DB 写，
 * 事务在这里既保护不了 Redis，还会把"已改 Redis、DB 回滚"变成新的不一致。
 */
@Slf4j
@Service
public class SeckillDeadLetterServiceImpl implements ISeckillDeadLetterService {

    /**
     * 补偿执行权标记 TTL。
     * <p>
     * 为什么是 7 天而不是 24 小时：这个 key 是"这条死信已经补过"的唯一证据，
     * TTL 一到、记录又还停在待补偿，重试就会<strong>再补一次</strong>，库存凭空 +1。
     * 7 天远大于"DB 连续不可用"的合理窗口，留足人工介入时间。
     */
    private static final long DONE_KEY_TTL_HOURS = 168L;

    /** reason 列是 varchar(255)，截断比插入报错好 */
    private static final int REASON_MAX_LENGTH = 250;

    /**
     * 人工重放锁 TTL（05）。一次重放 = 一条 UPDATE + 一次补偿，正常几十毫秒；
     * 给到 60 秒是兜"执行卡住"的情形：宁可 60 秒内不让重复点，也不要锁永久不释放。
     */
    private static final long REPLAY_LOCK_TTL_SECONDS = 60L;

    /**
     * 解锁脚本（05）：先比对 token 再删。
     * <p>
     * 直接 {@code DEL} 会踩一个经典坑：A 卡了 60 秒锁过期，B 拿到锁，
     * 此时 A 的 finally 才执行，把 B 的锁删掉 —— 锁就形同虚设。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);


    @Resource
    private SeckillDeadLetterMapper deadLetterMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void handle(SeckillOrderMessage msg, String payload, String reason) {
        SeckillDeadLetter record = new SeckillDeadLetter()
                .setOrderId(msg.getOrderId())
                .setUserId(msg.getUserId())
                .setVoucherId(msg.getVoucherId())
                .setPayload(truncate(payload, 500))
                .setReason(truncate(reason, REASON_MAX_LENGTH))
                .setStatus(SeckillDeadLetter.STATUS_PENDING)
                .setRetryCount(0);

        SeckillDeadLetter stored = null;
        try {
            deadLetterMapper.insert(record);
            stored = record;
        } catch (DuplicateKeyException dup) {
            // 至少一次投递：同一订单可能第二次进死信。唯一索引已经把"重复尸体"挡住，
            // 这里只需要判断"是否已经处理过"，处理过就只告警、绝不再补。
            stored = deadLetterMapper.selectOne(
                    new QueryWrapper<SeckillDeadLetter>().eq("order_id", msg.getOrderId()));
            if (stored != null && stored.getStatus() != null
                    && stored.getStatus() != SeckillDeadLetter.STATUS_PENDING) {
                log.warn("【死信】该订单此前已处理，本次仅告警不重复回补 | orderId={}, status={}, reason={}",
                        msg.getOrderId(), stored.getStatus(), reason);
                return;
            }
            log.warn("【死信】同一订单重复进入死信，复用已有记录 | orderId={}, id={}",
                    msg.getOrderId(), stored == null ? null : stored.getId());
        } catch (Exception e) {
            // 落库失败（DB 抖动 / 表还没建）不能阻断补偿：
            // 不回补 = 库存永久少一张（少卖）；不回写状态 = 只是少一条凭据，日志里还有 orderId，人工仍可追。
            log.error("【死信】落库失败，仍先执行库存补偿（宁可少凭据，不可少卖）| orderId={}, userId={}, voucherId={}",
                    msg.getOrderId(), msg.getUserId(), msg.getVoucherId(), e);
        }

        // 落库失败时用内存对象继续补偿，只是 markStatus 会因为 id 为空而跳过
        compensateAndMark(stored != null ? stored : record);
    }

    @Override
    public boolean compensateAndMark(SeckillDeadLetter record) {
        if (record == null || record.getOrderId() == null) {
            return false;
        }
        Long orderId = record.getOrderId();
        Long voucherId = record.getVoucherId();
        Long userId = record.getUserId();

        // ① 订单已落库 → 这不是死信，是误判。回补就是给一个已经下单成功的人凭空多一张券。
        if (voucherOrderMapper.selectById(orderId) != null) {
            markStatus(record, SeckillDeadLetter.STATUS_IGNORED, "订单已落库，误判死信，未回补");
            log.warn("【死信】订单已存在，属误判死信，不回补 | orderId={}", orderId);
            return false;
        }

        // ② 执行权：同一订单只允许一个线程真正回补（concurrency=2 + 重复投递都会撞上）。
        //    key 只在"回补成功"后保留；回补失败会删掉，让下一轮还能再试。
        String doneKey = RedisConstants.SECKILL_DLQ_DONE_KEY + orderId;
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(doneKey, "1", DONE_KEY_TTL_HOURS, TimeUnit.HOURS);
        if (!Boolean.TRUE.equals(first)) {
            // key 存在 ⇒ 之前已经补成功过（失败会删 key），这里只把状态补一致，不再动库存
            markStatus(record, SeckillDeadLetter.STATUS_COMPENSATED, null);
            log.warn("【死信】该订单已回补过，跳过库存操作 | orderId={}", orderId);
            return false;
        }

        // ③ 真正回补：库存 +1、用户从"已抢购"集合移除，让这张券能重新卖出去
        try {
            stringRedisTemplate.opsForValue()
                    .increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet()
                    .remove(RedisConstants.SECKILL_USER_KEY + voucherId, String.valueOf(userId));
        } catch (Exception e) {
            releaseDoneKey(doneKey);
            // 保持 status=0：下一轮对账任务会再试，运维也能从表里看到"这条还没补"
            log.error("【死信】回补 Redis 失败，保持待补偿 | orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId, e);
            return false;
        }

        markStatus(record, SeckillDeadLetter.STATUS_COMPENSATED, null);
        // 用 error 级别：库存口径变了，必须能被关键字告警抓到
        log.error("【死信】已回补 Redis 预扣 | orderId={}, voucherId={}, userId={}",
                orderId, voucherId, userId);
        return true;
    }

    @Override
    public int settlePending(int limit) {
        int max = Math.max(1, Math.min(limit, 500));
        List<SeckillDeadLetter> pending = deadLetterMapper.selectList(
                new QueryWrapper<SeckillDeadLetter>()
                        .eq("status", SeckillDeadLetter.STATUS_PENDING)
                        // 留 1 分钟给正常路径：消费者刚落库、正在补偿的那些，不要和它抢同一条
                        .lt("create_time", LocalDateTime.now().minusMinutes(1))
                        .orderByAsc("create_time")
                        .last("limit " + max));
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (SeckillDeadLetter record : pending) {
            if (compensateAndMark(record)) {
                ok++;
            }
        }
        log.warn("【死信】待补偿清单重试完成 | 本次成功 {}/{}", ok, pending.size());
        return ok;
    }

    @Override
    public long countPending(Long voucherId) {
        Integer count = deadLetterMapper.selectCount(new QueryWrapper<SeckillDeadLetter>()
                .eq("voucher_id", voucherId)
                .eq("status", SeckillDeadLetter.STATUS_PENDING));
        return count == null ? 0L : count;
    }

    @Override
    public List<SeckillDeadLetter> listByStatus(Integer status, int limit) {
        // 上限 200：payload 最长 500 字符，一次几百条就够看，再多只是把内存和前端一起拖慢
        int max = Math.max(1, Math.min(limit, 200));
        QueryWrapper<SeckillDeadLetter> wrapper = new QueryWrapper<SeckillDeadLetter>()
                .orderByDesc("create_time")
                .last("limit " + max);
        if (status != null) {
            wrapper.eq("status", status);
        }
        return deadLetterMapper.selectList(wrapper);
    }

    @Override
    public DeadLetterReplayVO replay(Long id) {
        if (id == null) {
            throw new BizException("死信 id 不能为空");
        }
        // 一把"按记录"的短锁：挡连点、挡两个运维同时点。
        // 即使锁失效，重复补偿也会被 compensateAndMark 里的 doneKey 拦住，
        // 这把锁保护的是 retry_count 的准确性（复盘时唯一的"人介入几次"证据）。
        String lockKey = RedisConstants.SECKILL_DLQ_REPLAY_LOCK_KEY + id;
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, REPLAY_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            throw new BizException("该死信正在重放中，请稍后刷新列表确认结果");
        }
        try {
            SeckillDeadLetter record = deadLetterMapper.selectById(id);
            if (record == null) {
                throw new BizException("死信记录不存在：id=" + id);
            }
            Integer before = record.getStatus();
            if (before != null && before == SeckillDeadLetter.STATUS_COMPENSATED) {
                // 这条闸不能只靠前端禁按钮：真正的库存错乱永远是"接口被直接调了一次"
                throw new BizException("该死信已补偿完成（orderId=" + record.getOrderId()
                        + "），再重放会让库存多归还一张");
            }

            // 一条 UPDATE 完成三件事：打回待补偿、清补偿时间、retry_count 原子 +1。
            // 不能写成"查出来 setRetryCount(x+1) 再 updateById"：并发下两次重放读到同一个旧值，计数会少加。
            deadLetterMapper.update(null, new UpdateWrapper<SeckillDeadLetter>()
                    .eq("id", id)
                    .set("status", SeckillDeadLetter.STATUS_PENDING)
                    .set("compensate_time", null)
                    .setSql("retry_count = retry_count + 1"));
            record.setStatus(SeckillDeadLetter.STATUS_PENDING);

            log.warn("【死信】人工重放开始 | id={}, orderId={}, voucherId={}, 重放前状态={}",
                    id, record.getOrderId(), record.getVoucherId(), before);
            // 关键：走的是同一条补偿链路，"先查 DB 订单、再抢 doneKey"两道闸一个都没绕过
            boolean compensated = compensateAndMark(record);

            SeckillDeadLetter after = deadLetterMapper.selectById(id);
            DeadLetterReplayVO vo = DeadLetterReplayVO.of(record, before, after, compensated);
            log.warn("【死信】人工重放结束 | id={}, orderId={}, 重放后状态={}, 本次动库存={}",
                    id, record.getOrderId(), vo.getStatusText(), compensated);
            return vo;
        } finally {
            unlockReplayLock(lockKey, token);
        }
    }

    /** 释放重放锁：只删自己的（见 {@link #UNLOCK_SCRIPT}） */
    private void unlockReplayLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 删不掉就等 TTL；这里抛异常会把"重放已成功"变成"重放报错"，运维会重复点第二次
            log.warn("【死信】释放重放锁失败，等 TTL 自动过期 | key={}", lockKey);
        }
    }

    /**
     * 回写状态。独立 try-catch：状态写失败不能反向影响已经做过的 Redis 补偿。
     * <p>
     * 用 {@code updateById} 而不是先查询再整行更新：MyBatis-Plus 默认忽略 null 字段，
     * 所以只有 set 过的列会进 SQL，不会把 payload / reason 覆盖成空。
     */
    private void markStatus(SeckillDeadLetter record, int status, String appendReason) {
        if (record.getId() == null) {
            log.warn("【死信】无落库记录，状态无法回写 | orderId={}, status={}", record.getOrderId(), status);
            return;
        }
        try {
            SeckillDeadLetter update = new SeckillDeadLetter()
                    .setId(record.getId())
                    .setStatus(status)
                    .setCompensateTime(status == SeckillDeadLetter.STATUS_COMPENSATED
                            ? LocalDateTime.now() : null);
            if (appendReason != null) {
                String existed = record.getReason() == null ? "" : record.getReason() + " | ";
                update.setReason(truncate(existed + appendReason, REASON_MAX_LENGTH));
            }
            deadLetterMapper.updateById(update);
            record.setStatus(status);
        } catch (Exception e) {
            log.error("【死信】状态回写失败，下一轮会重试 | id={}, orderId={}, targetStatus={}",
                    record.getId(), record.getOrderId(), status, e);
        }
    }

    private void releaseDoneKey(String doneKey) {
        try {
            stringRedisTemplate.delete(doneKey);
        } catch (Exception ignore) {
            // 删不掉就等 TTL 过期；这里再抛异常只会把"补偿失败"变成"补偿失败且没日志"
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
