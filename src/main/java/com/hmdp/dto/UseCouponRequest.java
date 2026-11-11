package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 核销请求体（施工手册 Day 5）。
 * <p>
 * 没有 userId：用户身份只能从 UserHolder 取。
 * 一旦让前端传 userId，就等于把「用谁的券」交给调用方决定，
 * 改个参数就能核销别人的券。
 */
@Data
public class UseCouponRequest {

    /** 订单号。目前没有订单表，由调用方（前端/测试）生成后传入 */
    private Long orderId;

    /** 下单店铺，用于校验商家券是否适用本店 */
    private Long shopId;

    /** 订单原价，单位「分」。与库表 int(11) 保持一致，不用 Long */
    private Integer orderAmount;

    /** 本次要使用的券，允许传多张（可叠加的场景） */
    private List<Long> userCouponIds;
}
