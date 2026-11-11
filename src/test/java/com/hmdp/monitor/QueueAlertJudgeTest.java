package com.hmdp.monitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列告警判定单测（秒杀 P0 · 05）。
 * <p>
 * 为什么这个功能能单测、而对账任务不能：判定规则只依赖"采样值 + 当前时间"两个输入，
 * 把它写成纯函数后，"持续 119 秒不报、121 秒报"这种边界可以在毫秒内验完。
 * 对账要连 Redis/MySQL/Rabbit 三件套，那是集成验证的范畴，不该混进来。
 */
class QueueAlertJudgeTest {

    /** 确认时长 120 秒、提醒间隔 600 秒、积压阈值 1000 条 —— 与 application.yaml 的默认值一致 */
    private static final long CONFIRM_SEC = 120L;
    private static final long REMIND_SEC = 600L;
    private static final long BACKLOG = 1000L;

    private static final String QUEUE = "seckill.queue";
    private static final long T0 = 1_000_000L;

    private static QueueAlertJudge judge() {
        return new QueueAlertJudge(CONFIRM_SEC, REMIND_SEC, BACKLOG);
    }

    private static QueueProbe.Stat stat(long ready, long consumers) {
        return new QueueProbe.Stat(true, ready, consumers);
    }

    private static long sec(long seconds) {
        return seconds * 1000L;
    }

    @Test
    void 抖动不足确认时长不告警_满120秒才告警() {
        QueueAlertJudge judge = judge();

        // 消费端本地重试窗口：ready>0、consumers=0 看起来像"没人消费"，其实只是消息还在客户手里
        assertEquals(QueueAlertJudge.Action.NONE, judge.onSample(QUEUE, stat(5, 0), T0).getAction());
        assertEquals(QueueAlertJudge.Action.NONE, judge.onSample(QUEUE, stat(5, 0), T0 + sec(60)).getAction());
        assertEquals(QueueAlertJudge.Action.NONE, judge.onSample(QUEUE, stat(5, 0), T0 + sec(119)).getAction());

        // 第 121 秒：确认窗口满了，升级为告警
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(5, 0), T0 + sec(121));
        assertEquals(QueueAlertJudge.Action.ALERT, d.getAction());
        assertEquals(QueueAlertJudge.Reason.NONE_CONSUMER, d.getReason());
        assertEquals(sec(121), d.getLastingMillis());
        assertTrue(judge.isAlerting(QUEUE));
    }

    @Test
    void 抖动中途恢复正常则重新计时() {
        QueueAlertJudge judge = judge();

        judge.onSample(QUEUE, stat(5, 0), T0);                       // 开始计时
        judge.onSample(QUEUE, stat(0, 0), T0 + sec(60));             // 消息被消费掉了 → 计时清零
        assertFalse(judge.isAlerting(QUEUE));
        assertEquals(0L, judge.pendingSeconds(QUEUE, T0 + sec(60)));

        // 重新违规：必须从新的时刻开始算，不能"接着上一次的 60 秒"
        judge.onSample(QUEUE, stat(5, 0), T0 + sec(100));
        assertEquals(QueueAlertJudge.Action.NONE,
                judge.onSample(QUEUE, stat(5, 0), T0 + sec(200)).getAction());
        // 100s + 120s = 220s 才满；200 秒时还差 20 秒
        assertEquals(100L, judge.pendingSeconds(QUEUE, T0 + sec(200)));
        assertEquals(QueueAlertJudge.Action.ALERT,
                judge.onSample(QUEUE, stat(5, 0), T0 + sec(221)).getAction());
    }

    @Test
    void 恢复后主动上报并清空状态() {
        QueueAlertJudge judge = judge();
        judge.onSample(QUEUE, stat(5, 0), T0);
        judge.onSample(QUEUE, stat(5, 0), T0 + sec(130));

        // 消费者被拉起：ready 被吃光、consumers=2
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(0, 2), T0 + sec(200));
        assertEquals(QueueAlertJudge.Action.RECOVERED, d.getAction());
        assertEquals(QueueAlertJudge.Reason.NONE_CONSUMER, d.getReason());
        assertEquals(sec(200), d.getLastingMillis());   // 一共坏了 200 秒（从第一次发现算起）
        assertFalse(judge.isAlerting(QUEUE));
        assertNull(judge.alertReason(QUEUE));

        // 再报一次恢复就不该有动作了（避免"解除"刷屏）
        assertEquals(QueueAlertJudge.Action.NONE,
                judge.onSample(QUEUE, stat(0, 2), T0 + sec(210)).getAction());
    }

    @Test
    void 告警期间同一原因保持安静_到提醒间隔才重复() {
        QueueAlertJudge judge = judge();
        judge.onSample(QUEUE, stat(5, 0), T0);
        assertEquals(QueueAlertJudge.Action.ALERT,
                judge.onSample(QUEUE, stat(5, 0), T0 + sec(120)).getAction());

        // 30 秒采样一次：120~720 秒之间这 20 轮都应该是 NONE，否则一小时 120 条 error
        for (long t = 150; t < 720; t += 30) {
            assertEquals(QueueAlertJudge.Action.NONE,
                    judge.onSample(QUEUE, stat(5, 0), T0 + sec(t)).getAction(), "t=" + t);
        }
        // 提醒间隔 = 上次打印 + 600 秒 = 720 秒
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(5, 0), T0 + sec(720));
        assertEquals(QueueAlertJudge.Action.REMIND, d.getAction());
        assertEquals(QueueAlertJudge.Reason.NONE_CONSUMER, d.getReason());
        assertEquals(720L, d.getLastingMillis() / 1000L);
    }

    @Test
    void 告警中原因变化立即再报一次() {
        QueueAlertJudge judge = judge();
        judge.onSample(QUEUE, stat(5, 0), T0);
        judge.onSample(QUEUE, stat(5, 0), T0 + sec(120));

        // 消费者回来了但产能不够：原因从"没人消费"变成"积压"，处置动作完全不同，必须立刻再报
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(2000, 2), T0 + sec(130));
        assertEquals(QueueAlertJudge.Action.ALERT, d.getAction());
        assertEquals(QueueAlertJudge.Reason.BACKLOG, d.getReason());
        assertEquals(QueueAlertJudge.Reason.BACKLOG, judge.alertReason(QUEUE));
    }

    @Test
    void 没人消费优先于积压_因为处置动作不同() {
        // 5000 条积压、0 个消费者：既是积压也是"没人消费"。
        // 必须报后者——前者会让人去调大阈值，后者才会让人去拉起消费者。
        QueueAlertJudge judge = judge();
        judge.onSample(QUEUE, stat(5000, 0), T0);
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(5000, 0), T0 + sec(121));
        assertEquals(QueueAlertJudge.Reason.NONE_CONSUMER, d.getReason());
    }

    @Test
    void 积压阈值边界() {
        QueueAlertJudge judge = judge();
        // 999 条、有消费者：正常水位，跑十分钟也不该报
        for (long t = 0; t <= 600; t += 30) {
            assertEquals(QueueAlertJudge.Action.NONE,
                    judge.onSample(QUEUE, stat(999, 2), T0 + sec(t)).getAction(), "t=" + t);
        }
        // 第 700 秒刚好到 1000：只是"开始计时"，不是立刻告警
        assertEquals(QueueAlertJudge.Action.NONE,
                judge.onSample(QUEUE, stat(1000, 2), T0 + sec(700)).getAction());
        // 821 秒：满 121 秒，升级
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, stat(1000, 2), T0 + sec(821));
        assertEquals(QueueAlertJudge.Action.ALERT, d.getAction());
        assertEquals(QueueAlertJudge.Reason.BACKLOG, d.getReason());
    }

    @Test
    void 读不到队列状态本身就要告警() {
        // UNREADABLE 的 ready/consumers 都是 -1：拿它去比阈值会得出"积压 -1 条"的荒谬结论，
        // 所以必须单独成一类，并且要能告警——监控失效比积压更危险。
        QueueAlertJudge judge = judge();
        judge.onSample(QUEUE, QueueProbe.Stat.UNREADABLE, T0);
        QueueAlertJudge.Decision d = judge.onSample(QUEUE, QueueProbe.Stat.UNREADABLE, T0 + sec(121));
        assertEquals(QueueAlertJudge.Action.ALERT, d.getAction());
        assertEquals(QueueAlertJudge.Reason.UNREADABLE, d.getReason());
        assertEquals(QueueAlertJudge.Action.RECOVERED,
                judge.onSample(QUEUE, stat(0, 2), T0 + sec(150)).getAction());
    }

    @Test
    void 多条队列互不影响() {
        QueueAlertJudge judge = judge();
        judge.onSample("seckill.queue", stat(5, 0), T0);
        judge.onSample("seckill.dlq", stat(0, 1), T0);

        assertEquals(QueueAlertJudge.Action.ALERT,
                judge.onSample("seckill.queue", stat(5, 0), T0 + sec(121)).getAction());
        // 死信队列一直是空的，不该被主队列的告警带进来
        assertFalse(judge.isAlerting("seckill.dlq"));
        assertEquals(QueueAlertJudge.Action.NONE,
                judge.onSample("seckill.dlq", stat(0, 1), T0 + sec(200)).getAction());
    }
}
