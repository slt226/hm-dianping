package com.hmdp.task;

import com.hmdp.monitor.QueueAlertJudge;
import com.hmdp.monitor.QueueProbe;
import com.hmdp.monitor.SeckillQueues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 队列健康采样任务（秒杀 P0 · 05）：默认每 30 秒看一眼主队列和死信队列。
 * <p>
 * 第四课把 {@code consumers} 打进了对账的门禁日志，但那只在"有人看日志"的时候才有用 ——
 * 消费者全挂时对账会<strong>永远</strong>拒绝修复（这是对的），却没有人会主动发现。
 * 本任务补的就是这个观测缺口：<strong>把"拒绝修复"翻译成一条 error 级别的告警</strong>。
 * <p>
 * 三个设计取舍：
 * <ol>
 *   <li><strong>30 秒采样、120 秒确认</strong>：采样要密（能及时看到趋势），确认要长（能把抖动滤掉）。
 *       两者分离，是这个任务唯一值得讲的地方；</li>
 *   <li><strong>不加 {@code @Transactional}、不写库</strong>：它只读队列状态、只打日志。
 *       写库会引入新的故障点——监控自己挂了不算最糟，最糟的是监控把库拖慢；</li>
 *   <li><strong>异常自己兜住</strong>：{@code @Scheduled} 抛出的异常会被线程池静默吞掉（只留一行日志），
 *       一旦某轮抛了，你会以为任务还在跑，其实它每轮都在抛。</li>
 * </ol>
 */
@Slf4j
@Component
public class SeckillQueueAlertTask {

    @Resource
    private QueueProbe queueProbe;

    @Resource
    private QueueAlertJudge queueAlertJudge;

    @Resource
    private SeckillQueues seckillQueues;

    /**
     * 采样入口。
     * <p>
     * 用 {@code fixedDelay} 而不是 {@code cron}：这条链路要的是"两次采样之间的间隔"，
     * 而不是"整分对齐"。若某轮因为 broker 慢阻塞了 5 秒，fixedDelay 会顺延而不是补跑，
     * 不会出现两轮背靠背采样把抖动误判成持续。
     */
    @Scheduled(fixedDelayString = "${seckill.alert.sample-interval-ms:30000}")
    public void sampleQueues() {
        try {
            long now = System.currentTimeMillis();
            for (String queue : seckillQueues.names()) {
                check(queue, now);
            }
        } catch (Exception e) {
            log.error("【队列告警】本轮采样整体失败（不影响下一轮）", e);
        }
    }

    /**
     * 采样单条队列并落日志。
     * <p>
     * 只有三种动作会打日志：ALERT / REMIND / RECOVERED。
     * 其余（正常、抖动未确认）走 debug —— 采样频率是 30 秒，info 级别会给日志文件制造噪音。
     */
    private void check(String queue, long now) {
        QueueProbe.Stat stat = queueProbe.stat(queue);
        QueueAlertJudge.Decision decision = queueAlertJudge.onSample(queue, stat, now);
        long seconds = decision.getLastingMillis() / 1000L;

        switch (decision.getAction()) {
            case ALERT:
                // error 级别：这条就应该是告警系统的输入（按关键字 【队列告警】 抓）
                log.error("【队列告警】{} | queue={}, {}, 已持续 {}s",
                        text(decision.getReason()), queue, stat.describe(), seconds);
                break;
            case REMIND:
                log.error("【队列告警·持续中】{} | queue={}, {}, 已持续 {}s",
                        text(decision.getReason()), queue, stat.describe(), seconds);
                break;
            case RECOVERED:
                // 恢复用 warn 而不是 info：它同样需要被看到，但不需要触发告警
                log.warn("【队列告警解除】{} | queue={}, {}, 共持续 {}s",
                        text(decision.getReason()), queue, stat.describe(), seconds);
                break;
            default:
                // NONE 一律不打日志：30 秒一轮 × 每条队列，info/debug 都会把日志文件灌满，
                // 而要确认"任务还活着"有更准的办法（/seckill/ops/queues）。
                break;
        }
    }

    /** 把原因翻译成"值班的人能照着做"的一句话 */
    private static String text(QueueAlertJudge.Reason reason) {
        if (reason == null) {
            return "未知原因";
        }
        switch (reason) {
            case NONE_CONSUMER:
                return "有消息但没有消费者（消费端可能已全部挂掉）";
            case BACKLOG:
                return "消费速度跟不上生产速度（ready 超过阈值）";
            case UNREADABLE:
                return "读不到队列状态（监控自身失效，需立刻查 broker）";
            default:
                return reason.name();
        }
    }
}
