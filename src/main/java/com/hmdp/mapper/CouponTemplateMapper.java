package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.utils.CouponConst;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {
    /**
     * 扣减库存：靠 UPDATE 的 WHERE 条件保证不会减成负数，
     * 影响行数是「有没有扣成功」的唯一判据（Day 3 领券用）。
     * 不加 status 条件的话，已下架的券还能被扣，所以带上 TPL_ON。
     */
    @Update("UPDATE tb_coupon_template SET remain_stock = remain_stock - 1 " +
            "WHERE id = #{id} AND status = " + CouponConst.TPL_ON + " AND remain_stock <> 0")
    int deductStock(@Param("id") Long id);

    /**
     * 归还库存：领券后续步骤失败（如唯一索引冲突）时补偿用。
     * <p>
     * 两个条件的有无都是有意的：
     * - 不加 status 条件：券可能刚好被下架，但库存必须还回去，否则凭空蒸发；
     * - 加 remain_stock != -1：不限量券的 -1 是「无限」的标记，
     *   一旦被加成 0，这张券就变成「0 张可领」了，是真实的资损 bug。
     */
    @Update("UPDATE tb_coupon_template SET remain_stock = remain_stock + 1 " +
            "WHERE id = #{id} AND remain_stock != -1")
    int increaseStock(@Param("id") Long id);
}
