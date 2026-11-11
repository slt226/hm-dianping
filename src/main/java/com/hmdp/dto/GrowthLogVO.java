package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成长值流水展示对象（施工手册 Day 8）。
 * <p>
 * 比实体多一个 reasonText。
 * <p>
 * 为什么不在前端做 {1:'消费',2:'签到'...} 这个映射：
 * 一旦加了新的 reason（比如「分享」），前端不改就是空白行。
 * 文案属于服务端可控的展示逻辑，跟着接口一起发版最省事。
 * <p>
 * changeAmount 保持带符号的原始值，前端按正负决定显示「+12」还是「-12」，
 * 服务端不再额外给一个 direction 字段——那是同一个信息存两遍。
 */
@Data
public class GrowthLogVO {

    private Long id;

    /** 带符号的变动值，正数增加、负数扣减 */
    private Integer changeAmount;

    private Integer reason;

    private String reasonText;

    /** 业务单号，前端一般不展示，排障时用得上 */
    private Long bizId;

    private LocalDateTime createTime;
}
