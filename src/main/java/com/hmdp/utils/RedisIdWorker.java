package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 基于 Redis 的全局唯一ID生成器。
     * <p>ID 结构（共 64 bit，long）：
     * <pre>
     *   1 bit  符号位 = 0
     *   31 bit 时间戳差值（秒级，基准 ID_WORKER_BEGIN_TIMESTAMP=2022-01-01 00:00:00 UTC）
     *   32 bit Redis 自增序列号（按 keyPrefix:yyyyMMdd 维度每天归零，每天单业务最多 2^32 个）
     * </pre>
     * <p>调用示例：
     * <pre>
     *   long orderId = redisIdWorker.nextId("order");
     *   long voucherOrderId = redisIdWorker.nextId("voucher-order");
     * </pre>
     *
     * @param keyPrefix 业务前缀，建议用短横线分隔的语义化名称，不要用冒号
     * @return 全局唯一、整体（近似）单调递增的 long 型ID
     */
    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 时间戳差值（秒级，31 bit）
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestampDelta = nowSecond - RedisConstants.ID_WORKER_BEGIN_TIMESTAMP;

        // 2. 序列号：key = icr:{keyPrefix}:{yyyyMMdd}
        //    用日期做后缀的目的：① 序列每天都从1开始，方便回溯；② 32 bit 足够承载单日 42 亿，不怕爆
        //    32 bit 满了？2^32 = 4,294,967,296，单业务日订单需 > 42 亿才会溢出，生产不会遇到
        String dateSuffix = now.format(DATE_FMT);
        String icrKey = RedisConstants.ICR_KEY_PREFIX + keyPrefix + ":" + dateSuffix;
        Long count = stringRedisTemplate.opsForValue().increment(icrKey);
        // Redis increment 在 key 不存在时会默认初始化成 0 再 +1 → 返回 1；如果没写 key 也返回 0（防御）
        if (count == null) {
            count = 1L;
        }

        // 3. 拼接：时间戳差值 << COUNT_BITS  |  序列号低32位
        //    用 & 0xFFFFFFFFL 把 count 压缩成 unsigned 32bit，避免 count 大了高位污染
        return (timestampDelta << RedisConstants.COUNT_BITS) | (count & 0xFFFFFFFFL);
    }
}
