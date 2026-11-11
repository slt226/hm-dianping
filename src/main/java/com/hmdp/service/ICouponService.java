package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.CouponTemplate;

/**
 * 券模板相关业务（施工手册 Day 2）。
 * <p>
 * 这里只管「券的定义」，不管「用户手里的券」——后者是 IUserCouponService。
 * 两者的分界线很清楚：CouponTemplate 是一批券的公共规则，
 * 一个人领没领、用没用，跟模板无关，写在 tb_user_coupon 里。
 */
public interface ICouponService {

    /**
     * 创建券模板（管理端接口）。
     * <p>
     * 建券时就把 remain_stock 初始化成 total_stock，
     * 并把库存预热到 Redis，保证 Day 3 领券时读取路径统一。
     */
    Result createTemplate(CouponTemplate t);

    /**
     * 某店铺可领的券列表（含全平台通用券）。
     * 店铺详情页下方那块「优惠」区域用的就是它。
     */
    Result listAvailable(Long shopId);

    /**
     * 平台券中心，分页。
     * 返回 MyBatis-Plus 的 Page 对象，前端直接取 records / total。
     */
    Result pageCenter(int page, int size);
}
