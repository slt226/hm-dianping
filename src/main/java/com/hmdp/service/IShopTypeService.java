package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询所有商品类型（按sort升序），优先走Redis缓存
     * - 缓存空值解决缓存穿透
     * - Redis不可用时降级直查DB
     */
    Result queryTypeList();
}
