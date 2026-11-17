package com.hmdp.service;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface ISeckillVoucherService extends IService<SeckillVoucher> {

    /**
     * 查券元数据（只保证 voucherId / beginTime / endTime 有值），走 Redis Hash 缓存。
     * <p>
     * 注意：<strong>不含 stock</strong>。库存的唯一真相在 Redis 的 seckill:stock key 里，
     * 数据库的 stock 是消费端落库后的滞后副本。
     *
     * @param voucherId 券 id
     * @return 券存在则返回；不存在返回 null
     */
    SeckillVoucher queryMetaById(Long voucherId);
}
