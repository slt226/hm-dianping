package com.hmdp.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 需要监控的队列名单（秒杀 P0 · 05）：{@code seckill.alert.queues}，逗号分隔。
 * <p>
 * 为什么不直接在任务里写死两个队列名：队列是会长的。将来加上"退款回补""库存校准"，
 * 新队列没进监控就等于没有监控，而这种漏配<strong>不会报错</strong>——
 * 它只会让某一天的事故"恰好没告警"。放在配置里，至少改配置的人会看到这一行。
 * <p>
 * 用 {@link LinkedHashSet} 去重：{@code seckill.queue,seckill.queue} 这种手误不该让同一条队列采样两次
 * （采样两次不会错，但会让"已持续"的时间跳变，日志时间线变得难读）。
 */
@Slf4j
@Component
public class SeckillQueues {

    private static final List<String> DEFAULTS = Arrays.asList("seckill.queue", "seckill.dlq");

    private final List<String> names;

    public SeckillQueues(@Value("${seckill.alert.queues:seckill.queue,seckill.dlq}") String queues) {
        Set<String> parsed = new LinkedHashSet<>();
        if (queues != null) {
            for (String q : queues.split(",")) {
                String name = q.trim();
                if (!name.isEmpty()) {
                    parsed.add(name);
                }
            }
        }
        if (parsed.isEmpty()) {
            // 不 fail fast：监控配错不应该让整个应用起不来。
            // 但必须留一行 warn —— 否则"监控自己空转"会安静得让人以为一切正常。
            log.warn("【队列告警】seckill.alert.queues 为空，回退到默认队列 {}", DEFAULTS);
            parsed.addAll(DEFAULTS);
        }
        this.names = Collections.unmodifiableList(new ArrayList<>(parsed));
        log.info("【队列告警】监控队列 {}", this.names);
    }

    /** 不可变列表，供采样任务与运维接口共用同一份名单 */
    public List<String> names() {
        return names;
    }
}
