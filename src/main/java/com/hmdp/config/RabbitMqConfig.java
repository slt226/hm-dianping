package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    private static final String SECKILL_EXCHANGE   = "seckill.exchange";
    private static final String SECKILL_QUEUE      = "seckill.queue";
    private static final String SECKILL_ROUTING_KEY= "seckill.key";

    //死信三件套：死信交换机 / 死信队列 / 死信路由键
    private static final String SECKILL_DLX        = "seckill.dlx";
    private static final String SECKILL_DLQ        = "seckill.dlq";
    private static final String SECKILL_DLQ_KEY    = "seckill.dlq.key";

    @Bean
    public DirectExchange seckillDirectExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }
    @Bean
    public Queue seckillQueue() {
        // x-dead-letter-* 是"退路"：消息被 reject(requeue=false) / 过期 / 队列超长时，
        // broker 会按这两个参数把它转发到死信交换机，不会再塞回自己队列。
        return QueueBuilder.durable(SECKILL_QUEUE)
                .withArgument("x-dead-letter-exchange", SECKILL_DLX)
                .withArgument("x-dead-letter-routing-key", SECKILL_DLQ_KEY)
                .build();
    }
    @Bean
    public Binding seckillBinding() {
        // 无参 + 直接调用 @Bean 方法：拿到的是容器里的单例，避免手写 new Queue 与声明不一致
        return BindingBuilder.bind(seckillQueue()).to(seckillDirectExchange()).with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public DirectExchange seckillDlxExchange() {
        return new DirectExchange(SECKILL_DLX);
    }
    @Bean
    public Queue seckillDlqQueue() {
        // 死信队列本身就是"人工介入的入口"，必须持久化，否则 broker 重启后尸体全丢、无法复盘
        return QueueBuilder.durable(SECKILL_DLQ).build();
    }
    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDlqQueue()).to(seckillDlxExchange()).with(SECKILL_DLQ_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
