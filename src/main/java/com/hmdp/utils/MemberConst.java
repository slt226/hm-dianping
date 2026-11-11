package com.hmdp.utils;

/**
 * 会员与成长值的枚举常量（施工手册 Day 8）。
 * <p>
 * 取值必须与 db/upgrade_v2_coupon_member_ai.sql 里 tb_growth_log.reason 的
 * COMMENT 一字不差。这类 tinyint 字段最容易出的问题是「代码里 1 是消费，
 * 库里注释写的是 1 是签到」，半年后没人能对上，所以两边要同步改。
 * <p>
 * 为什么不直接用 CouponConst：那个类是「券」的语义域，这个是「会员」的。
 * 混在一起会让 CouponConst 变成杂物间，而且两者的 reason 编号空间完全无关，
 * 哪天有人在券里也加一个 REASON_ORDER 就会撞名。
 */
public final class MemberConst {

    /** 普通会员，也是「查不到会员行」时的默认等级 */
    public static final int LEVEL_NORMAL = 0;

    /**
     * 成长值变动原因，与 tb_growth_log.reason 对应。
     * <p>
     * 注意 5 和 6 是<strong>扣减</strong>：change_amount 存负数。
     * 不设计成「正数 + 一个方向字段」，是因为流水表只在两种场景被读——
     * 展示明细和对账求和，用一个带符号的数这两件事都是一行 SQL。
     */
    public static final int REASON_ORDER  = 1;   // 消费
    public static final int REASON_SIGN   = 2;   // 签到
    public static final int REASON_COMMENT = 3;  // 评价
    public static final int REASON_INVITE = 4;   // 邀请
    public static final int REASON_EXPIRE = 5;   // 过期扣除
    public static final int REASON_REFUND = 6;   // 退款扣回

    /**
     * 消费 1 元累计多少成长值。
     * <p>
     * 写死在常量里是因为它同时被「加分」和「退款扣回」使用，
     * 两边必须完全一致——退款时按当前常量反算，如果运营中途改过比例，
     * 老订单会扣错。真要做动态比例，得在订单上快照当时的比例。
     */
    public static final int GROWTH_PER_YUAN = 1;

    /**
     * 单笔消费最多累计的成长值上限。
     * <p>
     * 防御性的：没有上限时，一次 10 万元的订单直接把用户顶到黑卡，
     * 而成长值体系的设计意图是「长期活跃」，不是「单笔大额」。
     */
    public static final int MAX_GROWTH_PER_ORDER = 500;

    private MemberConst() {
    }
}
