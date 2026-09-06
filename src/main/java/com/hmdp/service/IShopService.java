package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    /**
     * 按类型分页查询商铺
     * @param typeId  商铺类型
     * @param current 页码
     * @param sortBy  排序字段（comment/score，为空则按距离/默认）
     * @param x       用户经度（传入则启用 GEO 附近搜索）
     * @param y       用户纬度
     */
    Result queryShopByType(Integer typeId, Integer current, String sortBy, Double x, Double y);

    Result update(Shop shop);
}
