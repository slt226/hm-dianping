package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.TraceIds;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /**
     * 秒杀预扣 Lua 脚本：原子地完成 判断库存 + 一人一单校验 + 预扣 Redis 库存。
     * 返回 0=预扣成功  1=库存不足(含未预热)  2=已下过单。脚本启动时加载一次后复用。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        long userId = user.getId();

        // 券元数据（时间窗）走 Redis Hash 缓存，避免每个秒杀请求都打一次 MySQL
        SeckillVoucher sv = seckillVoucherService.queryMetaById(voucherId);
        if (sv == null) {
            return Result.fail("秒杀券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (sv.getBeginTime() != null && now.isBefore(sv.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (sv.getEndTime() != null && now.isAfter(sv.getEndTime())) {
            return Result.fail("秒杀已结束");
        }

        // 2. Lua脚本原子预扣：判断Redis库存 + 一人一单校验 + 预扣库存 + 记录用户
        //    售罄/重复请求在此被挡住，不再落到DB，大幅降低数据库并发压力
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String userKey = RedisConstants.SECKILL_USER_KEY + voucherId;
        Long r = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, userKey),
                String.valueOf(userId));
        if (r == null) {
            return Result.fail("系统异常");
        }
        if (r == 1L) {
            return Result.fail("库存不足");
        }
        if (r == 2L) {
            return Result.fail("每人限抢一张");
        }
        //3.生产订单号
        Long orderId=redisIdWorker.nextId("voucher-order");
        SeckillOrderMessage seckillOrderMessage = new SeckillOrderMessage(orderId,userId,voucherId);
        //4.投递信息 +broker确认
        CorrelationData correlationData =new CorrelationData(String.valueOf(orderId));
        // traceId 跨线程传递（任务 6）：MDC 是 ThreadLocal，消费者在另一个线程，
        // 只能靠消息头把它带过去。没有这一步，"生产→消费"两段日志永远串不起来。
        MessagePostProcessor traceIdHeader = message -> {
            String traceId = TraceIds.current();
            if (traceId != null) {
                message.getMessageProperties().setHeader(TraceIds.MQ_HEADER, traceId);
            }
            return message;
        };
        try{
          rabbitTemplate.convertAndSend("seckill.exchange","seckill.key",seckillOrderMessage,traceIdHeader,correlationData);
          CorrelationData.Confirm confirm=correlationData.getFuture().get(1, TimeUnit.SECONDS);
          if(!confirm.isAck()){
              throw new IllegalStateException("broker 拒收消息：" + confirm.getReason());
          }
            // ack 只代表"交换机收到了"，不代表"进了队列"。没路由到任何队列时这里非 null
            if(correlationData.getReturnedMessage()!=null){
                throw new IllegalStateException("消息无法路由到队列：" + correlationData.getReturnedMessage());
            }
        }catch (Exception e){
            log.error("秒杀消息投递失败，回滚 Redis 预扣 | orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId, e);
            rollbackRedis(voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }
        return Result.ok(orderId);
    }

    /**
     * 回滚 Redis 秒杀预扣：回补库存 + 移除用户记录。
     * Redis 不参与 Spring 事务，需手动补偿；try-catch 防止 Redis 故障影响主流程返回。
     */
    private void rollbackRedis(Long voucherId, long userId) {
        try {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            String userKey = RedisConstants.SECKILL_USER_KEY + voucherId;
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(userKey, String.valueOf(userId));
        } catch (Exception ignored) {
            // 回滚失败不影响主流程（极端情况下异步对账修复即可）
        }
    }
}
