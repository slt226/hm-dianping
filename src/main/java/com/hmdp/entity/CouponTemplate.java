package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_coupon_template")
public class CouponTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer type;
    private Integer acquireMode;
    private Long shopId;
    private Integer discountType;
    private Integer thresholdAmount;   // 分
    private Integer discountAmount;    // 分
    private Integer discountRate;      // 88 = 8.8 折
    private Integer maxDiscount;       // 折扣券封顶，0=不封顶
    private Integer payValue;
    private Integer totalStock;
    private Integer remainStock;
    private Integer perUserLimit;
    private Integer validType;         // 1固定区间 2领取后N天
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    private Integer validDays;
    private Integer memberLevel;
    private Integer stackable;
    private Long legacyVoucherId;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}