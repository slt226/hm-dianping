package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成长值流水（施工手册 Day 8）。
 * <p>
 * 这张表的<strong>真正价值不是记录，是那条唯一索引</strong>：
 * uk_user_reason_biz (user_id, reason, biz_id) 让「同一用户、同一原因、同一业务单号」
 * 在数据库层面只可能存在一行。
 * <p>
 * 换句话说，幂等不是靠 Java 里的 if 判断做出来的，是靠这条索引做出来的。
 * Java 代码只负责「撞了索引就当作已处理」。
 * <p>
 * 为什么 biz_id 允许为 NULL：签到、评价这类天然按天/按次唯一的场景不需要业务单号，
 * 而 MySQL 的唯一索引对 NULL 是「互不冲突」的，多行 NULL 可以共存——正好符合需求。
 * 但要注意：一旦 biz_id 为 NULL，索引就完全失去防重作用，
 * 这种场景必须靠别的机制保证（例如 reason + 日期拼成 biz_id 再用）。
 */
@Data
@TableName("tb_growth_log")
public class GrowthLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 本次变动值，正数增加、负数扣减（退款扣回），永远不存 0 */
    private Integer changeAmount;

    /** 变动原因，与 GrowthConst.REASON_* 对应 */
    private Integer reason;

    /**
     * 业务单号，用于幂等。
     * <p>
     * 消费场景传订单 id，签到场景可以传 yyyyMMdd 这样的日期序号。
     * 同一个 (userId, reason, bizId) 只会成功插入一次，这就是防重复加分的全部原理。
     */
    private Long bizId;

    private LocalDateTime createTime;
}
