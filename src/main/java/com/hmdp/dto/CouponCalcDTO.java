package com.hmdp.dto;

import com.hmdp.utils.CouponCalc;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CouponCalcDTO {
    private List<CouponCheck> usableList;
    private List<CouponCheck> unusableList;
    private List<Long>bestCouponIds;
    private BigDecimal originAmountYuan;
    private BigDecimal payAmountYuan;
    private BigDecimal savedAmountYuan;
}
