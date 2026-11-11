package com.hmdp.dto;

import lombok.Data;

/**
 * 成长值加分请求体（施工手册 Day 8，仅供自测与压测使用）。
 * <p>
 * 真实业务里成长值不该由前端触发——用户当然希望自己「消费」了 9999 元。
 * 生产环境应该是订单服务在订单完成时内部调用 addGrowth，
 * 前端根本接触不到这个入参。
 * <p>
 * 这里之所以开一个 HTTP 入口，唯一的目的是让你能并发打同一个 bizId，
 * 亲眼看到「10 个请求只加 1 次分」。验收完就应该把它当成调试接口看待。
 */
@Data
public class GrowthEarnRequest {

    /**
     * 业务单号，幂等的依据。
     * <p>
     * 压测时把它固定成同一个值并发打，就是检验幂等；改成不同值，就是检验不丢更新。
     */
    private Long bizId;

    /** 变动值，正数增加、负数扣减 */
    private Integer amount;

    /** 变动原因，见 MemberConst.REASON_*，不传默认按消费 */
    private Integer reason;
}
