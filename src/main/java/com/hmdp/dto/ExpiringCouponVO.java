package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 临期券视图（施工手册 Day 10）。
 * <p>
 * 与 {@link UserCouponVO} 的区别：那个是「我的券」列表用的，所有字段围绕用户自己看券；
 * 这个是运营口径的「即将过期的券」，所以带上了 {@code userId}
 * ——一张临期券要能追溯到是谁的，否则提醒发不出去。
 * <p>
 * {@code remainHours} 是服务端算好的，不让前端算：前端算的话，
 * 每个客户端时钟不一样，同一张券在两个人屏幕上显示的剩余时间会不同。
 */
@Data
public class ExpiringCouponVO {

    /** 用户券 id */
    private Long userCouponId;

    /** 持券人。运营提醒要发给他 */
    private Long userId;

    /** 券模板 id */
    private Long templateId;

    /** 券名称，来自模板 */
    private String name;

    /** 券类型，见 CouponConst.TYPE_* */
    private Integer couponType;

    /** 面额（元） */
    private Integer discountYuan;

    /** 门槛（元），0 表示无门槛 */
    private Integer thresholdYuan;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /**
     * 距过期还有多少小时。
     * <p>
     * 用 {@code ceil} 向上取整：还剩 10 分钟时显示「1 小时」比显示「0 小时」有用，
     * 后者看起来像已经过期了。
     */
    private Long remainHours;
}
