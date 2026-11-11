package com.hmdp.dto;

import com.hmdp.entity.SeckillDeadLetter;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 死信重放结果（秒杀 P0 · 05）。
 * <p>
 * 为什么不直接返回 {@code SeckillDeadLetter} 实体：调用方真正关心的是
 * <strong>"这次重放到底有没有动库存"</strong>，而这一点在表里看不出来 ——
 * {@code status=1} 既可能是本次补的，也可能是上次就补好了、这次只是把状态对齐。
 * 所以把 {@code compensated}（本次是否真的执行了归还）和 {@code statusBefore} 一起返回，
 * 人一眼就能判断"是补成功了，还是本来就没事"。
 */
@Data
@Accessors(chain = true)
public class DeadLetterReplayVO {

    private Long id;
    private Long orderId;
    private Long voucherId;
    private Long userId;

    /** 重放前的状态（0待补偿 1已补偿 2已忽略） */
    private Integer statusBefore;

    /** 重放后的状态 */
    private Integer statusAfter;

    /** 重放后状态的可读文本 */
    private String statusText;

    /** 本次重放是否<strong>真的</strong>归还了 Redis 预扣（false 表示没动库存） */
    private Boolean compensated;

    /** 累计重放次数（本次已计入） */
    private Integer retryCount;

    /** 给人看的结论 */
    private String message;

    /**
     * 按重放结果组装。
     *
     * @param before      重放前状态
     * @param after       重放后状态（null 表示读不到，按"未成功"处理）
     * @param compensated 本次是否真的动了 Redis
     */
    public static DeadLetterReplayVO of(SeckillDeadLetter record, Integer before,
                                        SeckillDeadLetter after, boolean compensated) {
        Integer statusAfter = after == null ? record.getStatus() : after.getStatus();
        return new DeadLetterReplayVO()
                .setId(record.getId())
                .setOrderId(record.getOrderId())
                .setVoucherId(record.getVoucherId())
                .setUserId(record.getUserId())
                .setStatusBefore(before)
                .setStatusAfter(statusAfter)
                .setStatusText(statusText(statusAfter))
                .setCompensated(compensated)
                .setRetryCount(after == null ? record.getRetryCount() : after.getRetryCount())
                .setMessage(explain(statusAfter, compensated));
    }

    /** 状态机三态的可读文本，和 {@link SeckillDeadLetter} 里的常量一一对应 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case SeckillDeadLetter.STATUS_PENDING:
                return "待补偿";
            case SeckillDeadLetter.STATUS_COMPENSATED:
                return "已补偿";
            case SeckillDeadLetter.STATUS_IGNORED:
                return "已忽略(误判死信)";
            default:
                return "未知(" + status + ")";
        }
    }

    /**
     * 结论文案。
     * <p>
     * 四个分支必须分开说：尤其"已补偿但本次没动库存"这一支，
     * 如果文案写成"补偿成功"，运维会以为库存被加了两次而跑去改库。
     */
    private static String explain(Integer statusAfter, boolean compensated) {
        if (statusAfter == null) {
            return "重放后状态读取失败，请刷新列表确认";
        }
        switch (statusAfter) {
            case SeckillDeadLetter.STATUS_COMPENSATED:
                return compensated
                        ? "重放成功：已归还 Redis 预扣（库存 +1，用户从已抢集合移除）"
                        : "该订单此前已回补过，本次仅把状态对齐为已补偿（库存未变）";
            case SeckillDeadLetter.STATUS_IGNORED:
                return "订单已在库中，仍判定为误判死信，未回补（如需回补请先核对订单）";
            case SeckillDeadLetter.STATUS_PENDING:
                return compensated
                        ? "已归还 Redis 预扣，但状态回写失败，下一轮对账会补齐状态"
                        : "重放失败：Redis 仍不可用，已保持待补偿，下一轮对账会再试";
            default:
                return "未知状态：" + statusAfter;
        }
    }
}
