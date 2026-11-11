package com.hmdp.utils;

import java.util.List;
import java.util.Map;

/**
 * 券与会员权益的枚举常量。
 * <p>
 * 取值必须与 db/upgrade_v2_coupon_member_ai.sql 中对应字段的 COMMENT 完全一致。
 * 改这里就要同步改 SQL 注释，反之亦然，否则线上排查时会看不懂库里那个 2 是什么意思。
 * <p>
 * 注意：本项目一个 tinyint 字段只有一套语义，但不同表的同名字段语义不同，
 * 例如 tb_coupon_template.status 是上下架，tb_user_coupon.status 是使用状态。
 * 所以下行常量的前缀不是装饰，是区分作用的，写代码时别混用。
 */
public final class CouponConst {

    /** 券类型：与 tb_coupon_template.type 对应 */
    public static final int TYPE_PLATFORM_REDPACK = 1;   // 平台红包
    public static final int TYPE_SHOP_COUPON      = 2;   // 商家券
    public static final int TYPE_MEMBER_REDPACK   = 3;   // 会员红包

    /** 获取方式：与 tb_coupon_template.acquire_mode 对应 */
    public static final int ACQUIRE_BUY    = 1;   // 付费购买（原秒杀券）
    public static final int ACQUIRE_CLAIM  = 2;   // 主动领取
    public static final int ACQUIRE_GRANT  = 3;   // 系统发放

    /** 优惠形式：与 tb_coupon_template.discount_type 对应 */
    public static final int DISCOUNT_FULL_CUT   = 1;   // 满减
    public static final int DISCOUNT_DIRECT     = 2;   // 无门槛直减
    public static final int DISCOUNT_RATE = 3;// 折扣


    /** 用户券状态：与 tb_user_coupon.status 对应（0未使用 1已使用 2已过期 3锁定中） */
    public static final int UC_UNUSED  = 0;
    public static final int UC_USED    = 1;
    public static final int UC_EXPIRED = 2;
    public static final int UC_LOCKED  = 3;   // 下单预占

    /**
     * 来源：与 tb_user_coupon.source 对应（1付费购买 2主动领取 3系统发放 4会员月度 5活动赠送）
     * <p>
     * 前三个值在语义上是 ACQUIRE_* 的子集，但字段用途不同：
     * acquire_mode 写在模板上，描述「这种券怎么获得」；
     * source 写在用户券上，记录「这张券实际是怎么来的」。
     * 别把 SRC_MEMBER / SRC_ACTIVITY 写进 tb_coupon_template.acquire_mode。
     */
    public static final int SRC_BUY        = 1;
    public static final int SRC_CLAIM      = 2;
    public static final int SRC_SYSTEM     = 3;
    public static final int SRC_MEMBER     = 4;
    public static final int SRC_ACTIVITY   = 5;

    /** 有效期类型：与 tb_coupon_template.valid_type 对应 */
    public static final int VALID_FIXED      = 1;   // 固定区间，看 valid_start / valid_end
    public static final int VALID_AFTER_DAYS = 2;   // 领取后 valid_days 天内有效

    /** 券模板上下架：与 tb_coupon_template.status 对应（注意与 UC_* 不是同一套语义） */
    public static final int TPL_OFF = 0;   // 下架
    public static final int TPL_ON  = 1;   // 上架

    /** 是否可叠加：与 tb_coupon_template.stackable 对应 */
    public static final int STACK_NO  = 0;   // 互斥，同单只能用一张
    public static final int STACK_YES = 1;   // 可与其他券叠加

    /** 全平台通用券在 shop_id 上的约定值，与 tb_coupon_template.shop_id 注释一致 */
    public static final long SHOP_ID_ALL = 0L;

    /**
     * 一笔订单最多使用的券张数。
     * <p>
     * 这个上限不是业务需求拍出来的，是防御性的：
     * 核销是「一张券一次条件 UPDATE」，张数越多事务越长、行锁持有越久，
     * 如果有人伪造 1000 个 id 打进来，能把下单接口拖垮。
     * 正常情况下叠加 2~3 张已经很少见。
     */
    public static final int MAX_USE_PER_ORDER = 10;

    private CouponConst() {
    }
}
