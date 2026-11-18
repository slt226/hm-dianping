package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_META_TTL;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    /** Hash field 名，必须与实体字段名保持 camelCase 一致，写错会静默拿到 null */
    private static final String FIELD_BEGIN_TIME = "beginTime";
    private static final String FIELD_END_TIME = "endTime";

    /**
     * 时间窗统一按东八区换算成秒级时间戳。
     * 用固定偏移量而不是系统默认时区，避免 JVM 时区变化导致时间窗整体漂移。
     */
    private static final ZoneOffset META_ZONE = ZoneOffset.ofHours(8);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public SeckillVoucher queryMetaById(Long voucherId) {
        // 1. 空参保护：不拦会拼出 seckill:meta:null 这种脏 key，还会存活 30 分钟
        if (voucherId == null) {
            return null;
        }
        String key = SECKILL_META_KEY + voucherId;

        // 2. 读缓存：一次 HGETALL 拿全部字段，不要 getHashValue 逐个取（3 次网络往返）
        Map<Object, Object> cache = stringRedisTemplate.opsForHash().entries(key);
        // entries 在 key 不存在时返回空 Map 而不是 null，所以判 isEmpty
        if (!cache.isEmpty()) {
            return buildMeta(voucherId,
                    fromEpochStr((String) cache.get(FIELD_BEGIN_TIME)),
                    fromEpochStr((String) cache.get(FIELD_END_TIME)));
        }

        // 3. 未命中才回源 DB
        SeckillVoucher sv = getById(voucherId);
        if (sv == null) {
            // 不做空值缓存：要抢券必须先拿到真实券 id，非法 id 请求量天然很小，
            // 为此引入"空标记"状态和独立 TTL，复杂度大于收益
            return null;
        }

        // 4. 回填缓存：一次 putAll，不要多次 put，否则中途失败会留下半截数据
        warmMeta(sv);
        // 5. 两条路径返回同一形状的对象：只保证 voucherId / beginTime / endTime 有值
        return buildMeta(voucherId, sv.getBeginTime(), sv.getEndTime());
    }

    private static SeckillVoucher buildMeta(Long voucherId, LocalDateTime beginTime, LocalDateTime endTime) {
        return new SeckillVoucher()
                .setVoucherId(voucherId)
                .setBeginTime(beginTime)
                .setEndTime(endTime);
    }

    private static String toEpochStr(LocalDateTime time) {
        return String.valueOf(time.toEpochSecond(META_ZONE));
    }

    private static LocalDateTime fromEpochStr(String epochSecond) {
        if (epochSecond == null) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(Long.parseLong(epochSecond), 0, META_ZONE);
    }
    @Override
    public void warmMeta(SeckillVoucher sv) {
        if (sv == null) {
            return;
        }
        String key = SECKILL_META_KEY + sv.getVoucherId();
        Map<String, String> meta = new HashMap<>(4);
        if (sv.getBeginTime() != null) {
            meta.put(FIELD_BEGIN_TIME, toEpochStr(sv.getBeginTime()));
        }
        if (sv.getEndTime() != null) {
            meta.put(FIELD_END_TIME, toEpochStr(sv.getEndTime()));
        }
        if (meta.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().putAll(key, meta);
        stringRedisTemplate.expire(key, SECKILL_META_TTL, TimeUnit.MINUTES);
    }
}
