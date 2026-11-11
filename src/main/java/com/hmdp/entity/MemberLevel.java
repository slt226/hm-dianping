package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员等级配置（施工手册 Day 8）。
 * <p>
 * 这是一张<strong>配置表</strong>，不是业务表：行数固定为 4（普通/银卡/金卡/黑卡），
 * 由运营维护，代码只读不写。
 * <p>
 * 主键就是 level 本身，用 IdType.INPUT 而不是 AUTO——等级是业务上定死的序号
 * （0/1/2/3），不是数据库生成的代理键。用自增会让「银卡 level=1」这种语义
 * 依赖插入顺序，一旦运营删过一行就全乱了。
 * <p>
 * 为什么等级规则不写在 Java 枚举里：门槛、折扣、权益文案都要随运营调整，
 * 写死在枚举里改一次就得发一次版。放表里改一行 SQL 就生效，
 * 而且以后做后台配置页是顺理成章的。
 */
@Data
@TableName("tb_member_level")
public class MemberLevel {
    /** 主键即等级序号，0=普通 1=银卡 2=金卡 3=黑卡 */
    @TableId(type = IdType.INPUT)
    private Integer level;

    private String name;

    /** 达到本级所需的累计成长值门槛（含） */
    private Integer growthThreshold;

    /** 每月红包总额（分） */
    private Integer monthlyRedpack;

    /** 每月发放红包张数 */
    private Integer monthlyCouponCnt;

    /**
     * 会员全场折扣，100 表示不打折，95 表示 95 折。
     * <p>
     * 用整数存百分比而不是 0.95 这种小数：金额计算全程走「分」，
     * 折扣也要能整除回分。用 double 会在 0.1+0.2 这类地方丢精度，
     * 对账时差 1 分钱要查半天。
     */
    private Integer discountRate;

    private String icon;

    /** 权益描述文案，直接展示给用户，不参与任何计算 */
    private String benefitDesc;

    private LocalDateTime createTime;
}
