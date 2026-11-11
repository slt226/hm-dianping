package com.hmdp.service.impl;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.ISeckillDeadLetterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 死信消费者（秒杀 P0 · 03 + 04）：只在"本地重试彻底耗尽"之后执行一次补偿。
 * <p>
 * 第三课时这个类自己承担"判断 + 回补 + 打日志"；第四课把业务搬进
 * {@link ISeckillDeadLetterService}，这里只剩<strong>翻译官</strong>的职责：
 * <ol>
 *   <li>从 AMQP 报文里取出<strong>原始 payload</strong>（人工重放要原样重投，不能靠反序列化再序列化）；</li>
 *   <li>从 {@code x-death} 头里取出死因（这是消息进死信后唯一还活着的上下文）；</li>
 *   <li>交给 service 完成"落库 → 补偿 → 回写状态"。</li>
 * </ol>
 * <p>
 * 注意：DONE 幂等标记、回补动作、状态机都已移入 service，
 * 因为对账任务每分钟还要把"补偿失败"的记录再试一次，逻辑不能只活在 MQ 监听器里。
 */
@Slf4j
@Component
public class SeckillDeadLetterConsumer {

    @Resource
    private ISeckillDeadLetterService deadLetterService;

    @RabbitListener(queues = "seckill.dlq")
    public void handleDeadLetter(SeckillOrderMessage msg, Message rawMessage) {
        // error 级别：死信=需要人来看，必须是告警级别，不能被 info 淹没
        String reason = describe(rawMessage.getMessageProperties());
        log.error("【死信告警】秒杀订单重试耗尽，进入死信队列 | orderId={}, userId={}, voucherId={}, {}",
                msg.getOrderId(), msg.getUserId(), msg.getVoucherId(), reason);
        // 原始报文：Jackson 反序列化会丢掉未知字段/格式细节，重放必须用原文
        String payload = rawMessage.getBody() == null
                ? null
                : new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        deadLetterService.handle(msg, payload, reason);
    }

    /**
     * 从 {@code x-death} 头里提炼死因。
     * <p>
     * 为什么不用异常堆栈：消息被 {@code reject(requeue=false)} 之后，异常对象早就随线程消失了，
     * 能带进死信队列的只有 broker 写的 {@code x-death}（reason / count / queue / time）。
     * 真想看到异常类型，需要在消费端 catch 里自己写进 headers。
     */
    private String describe(MessageProperties props) {
        Object header = props == null ? null : props.getHeader("x-death");
        if (!(header instanceof List) || ((List<?>) header).isEmpty()) {
            return "重试耗尽（无 x-death 头）";
        }
        Object first = ((List<?>) header).get(0);
        if (!(first instanceof Map)) {
            return "重试耗尽";
        }
        Map<?, ?> death = (Map<?, ?>) first;
        return "重试耗尽 reason=" + death.get("reason")
                + ", count=" + death.get("count")
                + ", queue=" + death.get("queue");
    }
}
