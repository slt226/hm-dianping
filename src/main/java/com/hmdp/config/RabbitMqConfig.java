package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    private static final String SECKILL_EXCHANGE   = "seckill.exchange";
    private static final String SECKILL_QUEUE      = "seckill.queue";
    private static final String SECKILL_ROUTING_KEY= "seckill.key";

    @Bean
    public DirectExchange seckillDirectExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }
    @Bean
    public Queue seckillQueue() {
        return  new Queue(SECKILL_QUEUE,true);
    }
    @Bean
    public Binding seckillBinding(Queue seckillQueue, DirectExchange seckillDirectExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillDirectExchange).with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
