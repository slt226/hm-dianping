package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_coupon_use_log")
public class CouponUseLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userCouponId;
    private Long userId;
    private Long orderId;
    private Long shopId;
    private Integer orderAmount;
    private Integer discountAmount;
    private Integer payAmount;
    private LocalDateTime createTime;
}
