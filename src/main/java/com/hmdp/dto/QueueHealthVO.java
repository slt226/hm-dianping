package com.hmdp.dto;

import com.hmdp.monitor.QueueAlertJudge;
import com.hmdp.monitor.QueueProbe;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 队列健康快照（秒杀 P0 · 05）。
 * <p>
 * 把"探针事实"和"判定结论"放在同一个对象里返回，是因为运维排查时永远要同时看这两个：
 * {@code ready=0} 但 {@code alerting=true} 说明刚恢复、状态还没翻过来；
 * {@code readable=false} 则说明下面几个数字根本不能信。
 */
@Data
@Accessors(chain = true)
public class QueueHealthVO {

    private String queue;

    /** 是否读到了队列状态；false 时 ready/consumers 都是 null，不要拿去做判断 */
    private Boolean readable;

    private Long ready;

    private Long consumers;

    /** 是否处于告警中（判定由 {@link QueueAlertJudge} 负责，含 120 秒确认窗口） */
    private Boolean alerting;

    /** 告警原因：NONE_CONSUMER / BACKLOG / UNREADABLE；未告警为 null */
    private String reason;

    /** 本次告警已持续秒数 */
    private Long alertSeconds;

    /** 违规已持续、但还没到确认时长的秒数（"快报警了"） */
    private Long pendingSeconds;

    public static QueueHealthVO of(String queue, QueueProbe.Stat stat,
                                   QueueAlertJudge judge, long nowMillis) {
        boolean readable = stat != null && stat.isOk();
        return new QueueHealthVO()
                .setQueue(queue)
                .setReadable(readable)
                .setReady(readable ? stat.getReady() : null)
                .setConsumers(readable ? stat.getConsumers() : null)
                .setAlerting(judge.isAlerting(queue))
                .setReason(judge.alertReason(queue) == null ? null : judge.alertReason(queue).name())
                .setAlertSeconds(judge.alertSeconds(queue, nowMillis))
                .setPendingSeconds(judge.pendingSeconds(queue, nowMillis));
    }
}
