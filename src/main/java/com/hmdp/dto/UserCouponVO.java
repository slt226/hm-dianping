package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的券」列表返回给前端的视图对象。
 * <p>
 * 为什么不能直接把 UserCoupon 实体返回：
 * 1. tb_user_coupon 只存 template_id，<strong>没有券名、门槛、折扣率</strong>，
 *    前端要显示「满 100 减 15」，必须 join 模板表。这个 join 由后端做一次，
 *    而不是让前端拿着 template_id 再发 N 个请求——那是典型的 N+1。
 * 2. 金额在库里是分，这里换成元。换算只允许发生在出参这一层，
 *    内部计算（CouponCalc）一律用分，避免小数参与运算丢精度。
 * 3. userId / templateId 这类字段对前端没有意义，露出去只是扩大攻击面。
 * <p>
 * 字段取舍标准：<strong>页面上要显示什么，就放什么</strong>，多一个都不加。
 */
@Data
public class UserCouponVO {

    /** 用户券实例 id，不是模板 id。核销时传的是这个 */
    private Long id;

    private String name;

    /** 券类型：1 平台红包 / 2 商家券 / 3 会员红包，见 CouponConst.TYPE_* */
    private Integer type;

    /** 优惠形式：1 满减直减 / 2 折扣，决定主视觉是「减 15 元」还是「8.8 折」 */
    private Integer discountType;

    /** 门槛（元），0 表示无门槛 */
    private Integer thresholdYuan;

    /** 优惠金额（元），折扣券为 0 */
    private Integer discountYuan;

    /** 折扣率，88 表示 8.8 折；非折扣券为 0 */
    private Integer discountRate;

    /** 折扣封顶（元），0 表示不封顶 */
    private Integer maxDiscountYuan;

    /** 0 表示全平台通用，非 0 是这个店铺专用 */
    private Long shopId;

    /** 0 未使用 / 1 已使用 / 2 已过期 / 3 锁定中 */
    private Integer status;

    /** 领取时间 */
    private LocalDateTime receiveTime;

    /**
     * 过期时间。
     * 注意这是领取那一刻就固定下来的（见 buildUserCoupon），
     * 不由 validType 现场推算——否则模板规则一改，老券的有效期会跟着变。
     */
    private LocalDateTime expireTime;

    /** 是否可与其他券叠加，前端打「可叠加」标签用 */
    private Boolean stackable;

    /**
     * 是否已过期（服务器时间算出来的）。
     * 不能让前端拿本地时间比 expireTime：用户把系统时间调回去年，
     * 过期券就能「复活」——虽然后端核销时仍会拦住，但界面会骗人。
     */
    private Boolean expired;
}
