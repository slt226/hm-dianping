package com.hmdp.dto;

import lombok.Data;

/**
 * 单张优惠券的可用性结论。
 * <p>
 * 只装结果，不做判断：判断逻辑在 CouponCalc.checkOne，这里只负责描述"能不能用、为什么不能用、差多少"。
 */
@Data
public class CouponCheck {

    /** 可用 */
    public static final String OK = "OK";
    /** 已使用 */
    public static final String USED = "USED";
    /** 锁定中（下单预占） */
    public static final String LOCKED = "LOCKED";
    /** 已过期 */
    public static final String EXPIRED = "EXPIRED";
    /** 不适用于当前店铺 */
    public static final String SHOP_MISMATCH = "SHOP_MISMATCH";
    /** 未达到使用门槛 */
    public static final String THRESHOLD = "THRESHOLD";

    /** 哪张用户券的结论 */
    private Long userCouponId;
    /** 是否可用 */
    private boolean usable;
    /** 原因码，供前端判断分支与 AI 客服使用 */
    private String reasonCode;
    /** 原因文案，直接展示给用户 */
    private String reasonText;
    /** 门槛差额，单位分，仅 THRESHOLD 时有值 */
    private int gapAmount;

    public static CouponCheck ok(Long userCouponId) {
        CouponCheck c = new CouponCheck();
        c.setUserCouponId(userCouponId);
        c.setUsable(true);
        c.setReasonCode(OK);
        c.setReasonText("可用");
        c.setGapAmount(0);
        return c;
    }
    public static CouponCheck fail(Long userCouponId, String reasonCode, String reasonText) {
        return fail(userCouponId, reasonCode, reasonText, 0);
    }
    public static CouponCheck fail(Long userCouponId,String code,String text,int gapAmount) {
        CouponCheck c = new CouponCheck();
        c.setUserCouponId(userCouponId);
        c.setUsable(false);
        c.setReasonCode(code);
        c.setReasonText(text);
        c.setGapAmount(gapAmount);
        return c;
    }

}
