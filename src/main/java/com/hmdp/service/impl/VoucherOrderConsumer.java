package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.segments.MergeSegments;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Component
public class VoucherOrderConsumer {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = "seckill.queue")
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(SeckillOrderMessage msg) {
        log.info("收到秒杀订单消息: {}", msg);
        Long voucherId = msg.getVoucherId();
        Long userId = msg.getUserId();
        Long orderId = msg.getOrderId();
        //1.DB扣库存（乐观锁CAS:只有stock>0时才进行扣减）
        boolean deductOk = seckillVoucherService.update(
                new UpdateWrapper<SeckillVoucher>()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0)
        );
        if(!deductOk) {
            log.warn("DB扣库存失败，voucherId={}", voucherId);
            rollbackRedis(voucherId, userId);
            return;
        }
        VoucherOrder order = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId)
                .setStatus(1)
                .setPayType(1)
                .setCreateTime(LocalDateTime.now());
        try{
            voucherOrderMapper.insert(order);
        }
        catch(DataIntegrityViolationException dup){
            //1.唯一索引冲突，同一用户下单
            log.warn("唯一索引冲突，userId={}, voucherId={}", userId, voucherId);
            //回补数据库库存
            seckillVoucherService.update(
                    new UpdateWrapper<com.hmdp.entity.SeckillVoucher>()
                            .setSql("stock = stock + 1")
                            .eq("voucher_id", voucherId)
            );
            //回滚数据库预扣
            rollbackRedis(voucherId,userId);
            return;
        }
        catch(Exception e){
            // 其他异常：回滚 Redis，抛出让 @Transactional 回滚 DB
            log.error("创建订单异常", e);
            rollbackRedis(voucherId, userId);
            throw e;
        }
        log.info("秒杀订单创建成功，orderId={}", orderId);
    }
    private void rollbackRedis(Long voucherId, Long userId){
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            String userKey = RedisConstants.SECKILL_USER_KEY + voucherId;
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(userKey, String.valueOf(userId));
        } catch (Exception ignored) {
            // 回滚失败不影响主流程
        }
    }
}