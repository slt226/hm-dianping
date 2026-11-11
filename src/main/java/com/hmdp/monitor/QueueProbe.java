package com.hmdp.monitor;

import com.rabbitmq.client.AMQP;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 队列探针（秒杀 P0 · 05）：一次 AMQP 被动声明，读出 ready 与 consumers。
 * <p>
 * 为什么把它从 {@code SeckillReconcileTask} 里抽出来：对账任务要知道"有没有在途"，
 * 告警任务要知道"有没有人在消费"，两处读的是<strong>同一条事实</strong>。
 * 各写一份的话，一旦哪天给探针加了超时或换了实现，两边就会给出不同的答案 ——
 * 运维会先怀疑自己看错，然后怀疑系统。
 * <p>
 * 用 {@code queueDeclarePassive} 而不是 management HTTP API 的三点理由：
 * <ol>
 *   <li>不引入 HTTP 客户端与 15672 的账号配置，少一份要同步的凭据；</li>
 *   <li>被动声明只读不建，队列不存在时 channel 会关掉，不会误建一个空队列掩盖问题；</li>
 *   <li>代价是<strong>拿不到 unacked 数</strong>——这正是对账要"两轮确认"、告警要"持续 N 秒"的原因。</li>
 * </ol>
 * 读不到（队列不存在 / broker 抖动 / channel 被关）时不抛异常，返回 {@link Stat#UNREADABLE}：
 * <strong>拿不到事实，和事实为零，是两件事</strong>，调用方必须分开处理。
 */
@Slf4j
@Component
public class QueueProbe {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 读取队列快照。任何异常都收敛成 {@link Stat#UNREADABLE}，绝不上抛。
     */
    public Stat stat(String queue) {
        try {
            Stat stat = rabbitTemplate.execute(channel -> {
                AMQP.Queue.DeclareOk ok = channel.queueDeclarePassive(queue);
                return new Stat(true, ok.getMessageCount(), ok.getConsumerCount());
            });
            return stat == null ? Stat.UNREADABLE : stat;
        } catch (Exception e) {
            // 逐条队列打日志：只打一行"探针失败"会让人以为是全局问题
            log.warn("【队列探针】读取队列状态失败，按\"不可判定\"处理 | queue={}, err={}", queue, e.getMessage());
            return Stat.UNREADABLE;
        }
    }

    /**
     * 队列快照值对象。
     * <p>
     * 不可变：探针拿到的是"某一瞬间的事实"，被后续代码改写会变成"猜"。
     */
    public static final class Stat {

        /** 读不到时的哨兵：ok=false 且 ready/consumers 都没有意义（用 -1 而不是 0，避免被当成"空闲"） */
        public static final Stat UNREADABLE = new Stat(false, -1, -1);

        private final boolean ok;
        private final long ready;
        private final long consumers;

        public Stat(boolean ok, long ready, long consumers) {
            this.ok = ok;
            this.ready = ready;
            this.consumers = consumers;
        }

        public boolean isOk() {
            return ok;
        }

        public long getReady() {
            return ready;
        }

        public long getConsumers() {
            return consumers;
        }

        /** 真的空闲：读到了，且 ready 为 0（unacked 看不到，所以调用方仍需二次确认） */
        public boolean isIdle() {
            return ok && ready == 0;
        }

        /** "有消息但没人消费"：消费者全挂的最典型形态 */
        public boolean isAbandoned() {
            return ok && ready > 0 && consumers == 0;
        }

        public String describe() {
            return ok ? "ready=" + ready + ", consumers=" + consumers : "unreadable";
        }

        @Override
        public String toString() {
            return "Stat{" + describe() + "}";
        }
    }
}
