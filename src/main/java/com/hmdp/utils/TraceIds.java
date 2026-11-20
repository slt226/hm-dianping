package com.hmdp.utils;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * traceId 的唯一约定点（任务 6）。
 * <p>
 * 三个常量必须只有一份定义：HTTP 头、MDC key、MQ 消息头。
 * 如果散落在 Filter / 生产者 / 消费者三处各写一遍字符串，
 * 改一个忘一个时，症状是"日志里一半有 traceId 一半没有"，极难排查。
 * <p>
 * 全部方法都是静态的：{@link MDC} 本身就是 ThreadLocal，不需要实例状态，
 * 做成 Bean 反而会在拦截器、MQ 监听器这些非 Spring 管理的位置拿不到。
 */
public final class TraceIds {

    /** HTTP 请求/响应头。网关或前端已有链路 id 时透传，便于和服务端日志对齐 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC key，必须和 application.yaml 里 logback pattern 的 %X{traceId} 一致 */
    public static final String MDC_KEY = "traceId";

    /** MQ 消息头，跨线程传递的唯一载体 */
    public static final String MQ_HEADER = "traceId";

    /** 上游传进来的值不可信：超长头会污染日志行，截断到 64 字符 */
    private static final int MAX_LENGTH = 64;

    private TraceIds() {
    }

    public static void put(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    /**
     * 只 remove 自己的 key，不用 {@code MDC.clear()}：
     * clear 会连带清掉别的组件（如 MyBatis、Actuator）放进去的诊断上下文。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 16 位足够单机压测期内不碰撞，且比 32 位 UUID 好读、不占日志宽度 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 优先用上游传进来的，没有就生成一个；顺手做长度截断 */
    public static String resolve(String fromUpstream) {
        if (fromUpstream == null) {
            return generate();
        }
        String trimmed = fromUpstream.trim();
        if (trimmed.isEmpty()) {
            return generate();
        }
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
