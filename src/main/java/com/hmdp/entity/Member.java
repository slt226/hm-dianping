package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户会员信息（施工手册 Day 8）。
 * <p>
 * 一个用户最多一行，靠 uk_user 唯一索引保证。
 * 这行记录<strong>不是</strong>注册时就创建，而是第一次产生成长值时懒创建——
 * 理由很实际：tb_user 有几十万行，绝大多数用户一辈子都是 0 级，
 * 提前给每人插一行会员记录等于凭空多一张几十万行的表，而它们的内容全是默认值。
 * <p>
 * 所以读的时候必须容忍「查不到」：查不到等价于 level=0、growth=0。
 * 这条约定贯穿 IMemberService 的所有读取方法，也是 addGrowth 里要做 upsert 的原因。
 * <p>
 * growth 是<strong>累计值</strong>，只增不减（退款扣回是加负数，不是清零重算）。
 * 有了累计值就不需要每次升级都反查流水求和，等级判定是 O(1) 比较，不是聚合查询。
 */
@Data
@TableName("tb_member")
public class Member {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 当前等级，与 tb_member_level.level 对应，0=普通会员 */
    private Integer level;

    /** 累计成长值，只增不减 */
    private Integer growth;

    /**
     * 付费会员到期时间，NULL 表示永久（成长值会员）。
     * <p>
     * 本项目只用了成长值体系，这个字段保留是为了把「付费」和「成长」两套体系分开，
     * 免得以后接付费会员时，把到期逻辑硬塞进 level 字段里。
     */
    private LocalDateTime expireTime;

    /** 自动续费标记，当前未接入支付，仅占位 */
    private Boolean autoRenew;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
