package com.hmdp.monitor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队列异常判定状态机（秒杀 P0 · 05）：纯逻辑、无 IO、可单测。
 * <p>
 * 为什么要把"判定"从定时任务里拆出来：判定规则是这个功能里<strong>唯一容易写错</strong>的部分
 * （抖动窗口、恢复时机、重复告警的抑制），而它全靠时间推进。留在 {@code @Scheduled} 方法里，
 * 想验证"持续 119 秒不报、121 秒报"就得真的等两分钟，还得祈祷期间没人发消息。
 * 拆成"喂采样 + 给时间戳"的纯函数后，同一套规则可以在毫秒内跑完全部边界。
 * <p>
 * 三件事只在第一次违规后发生：
 * <ol>
 *   <li>采样：违规要<strong>持续</strong> {@code confirmMillis} 才升级为告警 ——
 *       消费端本地重试窗口、消费者重启、broker 抖动都会让 ready 短暂大于 0，一律报错等于把告警训练成噪音；</li>
 *   <li>告警期间同一原因<strong>保持安静</strong>，只按 {@code remindMillis} 定期提醒 ——
 *       否则 30 秒采样一次，一小时就是 120 条 error，真正的告警会被淹没；</li>
 *   <li>恢复时<strong>主动</strong>报一条 RECOVERED —— 没有"解除"通知的告警系统，
 *       值班的人永远不知道现在到底还在不在故障里。</li>
 * </ol>
 */
@Component
public class QueueAlertJudge {

    /** 违规原因：谁在坏 */
    public enum Reason {
        /** 有消息、没消费者 —— 消费者全挂，积压只会越来越多 */
        NONE_CONSUMER,
        /** 有消费者但 ready 超过阈值 —— 消费能力跟不上生产速度 */
        BACKLOG,
        /** 读不到队列状态 —— 监控自身失效，比积压更危险（它让人误以为"一切正常"） */
        UNREADABLE
    }

    /** 本次采样产生的动作 */
    public enum Action {
        /** 什么都不用做（正常 / 违规未到确认时长 / 告警中但未到提醒时间） */
        NONE,
        /** 首次告警，或原因变化后重新告警 */
        ALERT,
        /** 告警持续中的定期提醒 */
        REMIND,
        /** 已恢复 */
        RECOVERED
    }

    /** 一次采样的判定结果 */
    public static final class Decision {

        static final Decision NOTHING = new Decision(Action.NONE, null, 0L);

        private final Action action;
        private final Reason reason;
        private final long lastingMillis;

        Decision(Action action, Reason reason, long lastingMillis) {
            this.action = action;
            this.reason = reason;
            this.lastingMillis = lastingMillis;
        }

        public Action getAction() {
            return action;
        }

        public Reason getReason() {
            return reason;
        }

        /** 这次违规已经持续了多久（RECOVERED 时是"一共坏了多久"） */
        public long getLastingMillis() {
            return lastingMillis;
        }

        @Override
        public String toString() {
            return action + (reason == null ? "" : "(" + reason + ")") + " " + lastingMillis + "ms";
        }
    }

    /** 每条队列自己的状态，互不影响 */
    private static final class State {
        /** 本次违规第一次被看到的时刻；-1 表示当前没有违规 */
        private long pendingSince = -1L;
        private Reason pendingReason;
        private boolean alerting;
        private Reason alertReason;
        /** 本次告警第一次被确认的时刻（不是发现时刻） */
        private long alertSince = -1L;
        private long lastLogAt = -1L;
    }

    private final long confirmMillis;
    private final long remindMillis;
    private final long backlogThreshold;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public QueueAlertJudge(@Value("${seckill.alert.confirm-seconds:120}") long confirmSeconds,
                           @Value("${seckill.alert.remind-seconds:600}") long remindSeconds,
                           @Value("${seckill.alert.backlog-threshold:1000}") long backlogThreshold) {
        // 三个参数都要防 0：confirm=0 会让抖动直接变成告警，remind=0 会让每轮都重复报，
        // backlog=0 则把"有 1 条待消费"当成积压（而这恰恰是常态）。
        this.confirmMillis = Math.max(1L, confirmSeconds) * 1000L;
        this.remindMillis = Math.max(1L, remindSeconds) * 1000L;
        this.backlogThreshold = Math.max(1L, backlogThreshold);
    }

    /**
     * 喂一次采样。
     *
     * @param queue     队列名
     * @param stat      探针结果
     * @param nowMillis 当前时间（由调用方传入，才能单测时间推进）
     */
    public synchronized Decision onSample(String queue, QueueProbe.Stat stat, long nowMillis) {
        State s = states.computeIfAbsent(queue, k -> new State());
        Reason reason = classify(stat);

        if (reason == null) {
            // 正常：清掉"违规进行中"的计时；若原本在告警，则发一条恢复
            s.pendingSince = -1L;
            s.pendingReason = null;
            if (!s.alerting) {
                return Decision.NOTHING;
            }
            Decision recovered = new Decision(Action.RECOVERED, s.alertReason, nowMillis - s.alertSince);
            s.alerting = false;
            s.alertReason = null;
            s.alertSince = -1L;
            s.lastLogAt = -1L;
            return recovered;
        }

        if (s.pendingReason != reason) {
            // 换了原因（或刚开始违规）：重新计时，"已持续"必须从 0 算
            s.pendingReason = reason;
            s.pendingSince = nowMillis;
        }

        if (!s.alerting) {
            if (nowMillis - s.pendingSince < confirmMillis) {
                // 关键分支：抖一下不报。消费端本地重试 1s+2s、消费者重启几秒，都落在这一窗口
                return Decision.NOTHING;
            }
            s.alerting = true;
            s.alertReason = reason;
            s.alertSince = s.pendingSince;
            s.lastLogAt = nowMillis;
            return new Decision(Action.ALERT, reason, nowMillis - s.pendingSince);
        }

        if (s.alertReason != reason) {
            // 告警中原因变了（没人消费 → 积压，或积压 → 不可读）：必须立刻再报一次，
            // 因为处置动作完全不同（前者拉起消费者，后者查 broker）
            s.alertReason = reason;
            s.alertSince = s.pendingSince;
            s.lastLogAt = nowMillis;
            return new Decision(Action.ALERT, reason, nowMillis - s.pendingSince);
        }

        if (nowMillis - s.lastLogAt >= remindMillis) {
            s.lastLogAt = nowMillis;
            return new Decision(Action.REMIND, reason, nowMillis - s.alertSince);
        }
        return Decision.NOTHING;
    }

    /**
     * 判定单次采样属于哪一类违规；返回 null 表示正常。
     * <p>
     * 顺序有意义：<strong>不可读 &gt; 没人消费 &gt; 积压</strong>。
     * 不可读放最前，是因为此时 ready/consumers 都没有意义（都是 -1），
     * 拿它去比阈值会得出"积压 -1 条"这种荒谬结论。
     */
    private Reason classify(QueueProbe.Stat stat) {
        if (stat == null || !stat.isOk()) {
            return Reason.UNREADABLE;
        }
        if (stat.isAbandoned()) {
            return Reason.NONE_CONSUMER;
        }
        if (stat.getReady() >= backlogThreshold) {
            return Reason.BACKLOG;
        }
        return null;
    }

    /** 当前是否处于告警中（给运维接口看） */
    public synchronized boolean isAlerting(String queue) {
        State s = states.get(queue);
        return s != null && s.alerting;
    }

    /** 当前告警原因（未告警返回 null） */
    public synchronized Reason alertReason(String queue) {
        State s = states.get(queue);
        return s == null || !s.alerting ? null : s.alertReason;
    }

    /** 本次告警已持续多少秒（未告警返回 0） */
    public synchronized long alertSeconds(String queue, long nowMillis) {
        State s = states.get(queue);
        if (s == null || !s.alerting || s.alertSince < 0) {
            return 0L;
        }
        return Math.max(0L, (nowMillis - s.alertSince) / 1000L);
    }

    /** 违规已持续但还没到确认时长的秒数（"快报警了"的观察值） */
    public synchronized long pendingSeconds(String queue, long nowMillis) {
        State s = states.get(queue);
        if (s == null || s.pendingSince < 0 || s.alerting) {
            return 0L;
        }
        return Math.max(0L, (nowMillis - s.pendingSince) / 1000L);
    }
}
