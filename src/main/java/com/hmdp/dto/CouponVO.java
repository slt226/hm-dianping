package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 券列表返回给前端的视图对象。
 * <p>
 * 与 CouponTemplate 的区别只有两点，但都很关键：
 * 1. 金额单位：库里存「分」，这里给「元」。分是整数、不会丢精度，
 *    换算放在出参这一层做，后端内部一律用分计算（见 CouponCalc）。
 * 2. 补了前端算不出来的字段：expired（当前是否在可领窗口内）、
 *    remainStock（还剩几张）。前者要拿服务器时间和券的有效期比，
 *    交给前端算就会出现「用户改本地时间」的漏洞。
 * <p>
 * 注意别把 totalStock / perUserLimit 之外的后台字段（如 payValue）
 * 一股脑塞进来，券列表是给用户看的，不是给运营看的。
 */
@Data
public class CouponVO {

    private Long id;
    private String name;

    /** 券类型：见 CouponConst.TYPE_* */
    private Integer type;
    /** 优惠形式：见 CouponConst.DISCOUNT_* */
    private Integer discountType;

    /** 0 表示全平台通用 */
    private Long shopId;

    /** 使用门槛（元） */
    private Integer thresholdYuan;
    /** 优惠金额（元），折扣券为 0 */
    private Integer discountYuan;
    /** 折扣率，88 表示 8.8 折 */
    private Integer discountRate;
    /** 折扣券封顶金额（元），0 表示不封顶 */
    private Integer maxDiscountYuan;

    /** 领取后 N 天有效时的天数，validType=2 才有意义 */
    private Integer validDays;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;

    /** 每人限领张数 */
    private Integer perUserLimit;
    /** 剩余库存，-1 表示不限量 */
    private Integer remainStock;

    /** 是否可与其他券叠加，前端打「可叠加」标签用 */
    private Boolean stackable;

    /** true=已过期或尚未开始，前端据此把按钮置灰 */
    private Boolean expired;
}
