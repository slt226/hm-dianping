package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀死信记录（秒杀 P0 · 04）。
 * <p>
 * 第三课把死信 ack 掉之后，"尸体"只存在日志里：查不了、统计不了、也没法重放。
 * 这张表是死信的<strong>唯一凭据</strong>，同时给对账任务提供两个关键输入：
 * <ol>
 *   <li>该券还有几条死信<strong>没补偿</strong>（{@code status=0}）——
 *       它们是"已预扣未落库"的在途量，对账时必须排除，否则会把库存抬高造成超卖；</li>
 *   <li>哪些订单需要人工补单/重放（{@code payload} 存了原始报文，不靠猜）。</li>
 * </ol>
 * 状态机（不能用一个 boolean 表达，否则"忘了补"和"决定不补"分不开）：
 * <pre>
 *   0 待补偿 ──补偿成功──▶ 1 已补偿
 *      └────订单已落库(误判)──▶ 2 已忽略
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_dead_letter")
public class SeckillDeadLetter implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待补偿：Redis 预扣还没归还，计入对账的在途量 */
    public static final int STATUS_PENDING = 0;

    /** 已补偿：库存和用户集合都已归还 */
    public static final int STATUS_COMPENSATED = 1;

    /** 已忽略：订单其实已经落库，属于误判死信，绝不能回补（回补=凭空多一张券） */
    public static final int STATUS_IGNORED = 2;

    /**
     * 主键（自增）
     * <p>
     * 不用 order_id 做主键：order_id 是业务键，将来若要允许"同一订单多次死信"的分片记录，
     * 主键被业务键绑死就没法扩展；而唯一索引已经足够承担幂等职责。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 业务订单号（唯一索引 uk_order_id） */
    private Long orderId;

    /** 下单用户id */
    private Long userId;

    /** 秒杀券id */
    private Long voucherId;

    /** 原始消息 JSON：人工重放时原样重投，不靠猜 */
    private String payload;

    /** 失败原因摘要（x-death 的 reason/count/queue） */
    private String reason;

    /** 0待补偿 1已补偿 2已忽略 */
    private Integer status;

    /** 人工重放次数 */
    private Integer retryCount;

    /** 补偿完成时间 */
    private LocalDateTime compensateTime;

    /** 进入死信时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
