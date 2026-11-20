package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.TraceIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
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

    /**
     * 消费秒杀订单消息。
     * <p>
     * 第二个参数 {@link Message} 是 Spring AMQP 的特殊支持：{@code @RabbitListener} 方法
     * 可以在业务对象之后追加一个 {@code Message} 形参，拿到原始报文和消息头。
     * 这里加它是为了取生产者塞进去的 {@code traceId}（任务 6）。
     * <p>
     * ⚠️ 事务注解必须留在这个方法上：拆成"外层设 MDC + 内层调 @Transactional 方法"会变成自调用，
     * 不经过 Spring 代理，事务直接失效 —— 这是本地最典型的"事务无声失灵"。
     */
    @RabbitListener(queues = "seckill.queue")
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(SeckillOrderMessage msg, Message rawMessage) {
        // 消费者线程是复用的，先把生产端的 traceId 还原进 MDC；没有就自己生成一个，
        // 保证"消费段日志"至少有 id 可用，而不是一段空白的 [ ]
        Object header = rawMessage.getMessageProperties().getHeaders().get(TraceIds.MQ_HEADER);
        TraceIds.put(header == null ? null : String.valueOf(header));
        if (TraceIds.current() == null) {
            TraceIds.put(TraceIds.generate());
        }
        try {
            log.info("收到秒杀订单消息: {}", msg);
            Long voucherId = msg.getVoucherId();
            Long userId = msg.getUserId();
            Long orderId = msg.getOrderId();
            //0.幂等门禁：消息可能被投递多次（网络重投 / ack丢失 / 生产端补偿 / 集群切换）。
            //  订单已存在就直接返回，绝不能重复扣库存，更不能回滚一笔已经成功的好单。
            //  注意：必须放在"扣库存"之前，否则副作用已经发生，再拦就晚了。
            if (voucherOrderMapper.selectById(orderId) != null) {
                log.info("订单已存在，忽略重复消息 | orderId={}", orderId);
                return;
            }

            //1.DB扣库存（乐观锁CAS:只有stock>0时才进行扣减）
            boolean deductOk = seckillVoucherService.update(
                    new UpdateWrapper<SeckillVoucher>()
                            .setSql("stock = stock - 1")
                            .eq("voucher_id", voucherId)
                            .gt("stock", 0)
            );
            if (!deductOk) {
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
            try {
                voucherOrderMapper.insert(order);
            } catch (DataIntegrityViolationException dup) {
                //2.兜底：并发下两条相同 orderId 的消息可能同时通过门禁（RR 隔离级别互相看不见对方的 insert），
                //  主键冲突说明"别人已经把这单建好了"。此时本事务自己扣掉的那 1 张要还回去，
                //  但绝对不能动 Redis —— 那会把一个已经下单成功的用户重新解锁，凭空多出一张券。
                log.warn("订单已存在（并发重复），回补本次预扣 | orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId);
                seckillVoucherService.update(
                        new UpdateWrapper<SeckillVoucher>()
                                .setSql("stock = stock + 1")
                                .eq("voucher_id", voucherId)
                );
                return;
            } catch (Exception e) {
                // 其他异常：只抛出，让 @Transactional 回滚 DB，然后交给容器的 retry 策略重试。
                // ⚠️ 绝不能在这里回补 Redis：本地重试会重复调用本方法，回补会被放大 N 倍。
                //    实测：失败 82 次 → Redis 库存 97 被回补到 179，闸门彻底失效。
                //    回补统一挪到"重试彻底耗尽"之后的 SeckillDeadLetterConsumer，每条死信只补一次。
                //    原则：宁可少卖（死信可人工补），不可超卖（DB 无货却卖出去了）。
                log.error("创建订单异常，交由容器重试 | orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId, e);
                throw e;
            }
            log.info("秒杀订单创建成功，orderId={}", orderId);
        } finally {
            TraceIds.clear();
        }
    }

    private void rollbackRedis(Long voucherId, Long userId) {
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
