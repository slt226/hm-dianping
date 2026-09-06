package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 空列表在缓存中用的特殊标记（不要存 []，因为 JSONUtil.toList 对 "[]" 会返回空列表，
     * 无法区分"DB真的为空"和"缓存有值但为空"）。
     * 这里约定：用字符串 "__EMPTY_LIST__" 表示缓存了空结果。
     */
    private static final String EMPTY_LIST_MARK = "__EMPTY_LIST__";

    @Override
    public Result queryTypeList() {
        // 1.从Redis查询缓存（所有商品类型按sort排序后整体作为一条list缓存）
        String cacheJson;
        try {
            cacheJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        } catch (Exception e) {
            log.warn("queryTypeList: Redis不可用，降级直查DB | err={}", e.toString());
            return Result.ok(queryTypeListFromDb());
        }

        // 2.缓存非空字符串命中
        if (StrUtil.isNotBlank(cacheJson)) {
            // 命中空列表标记 → DB里也没数据，直接返回空列表
            if (EMPTY_LIST_MARK.equals(cacheJson)) {
                return Result.ok(Collections.emptyList());
            }
            // 显式反序列化为 List<ShopType>，避免强转为 List<LinkedHashMap> 的坑
            List<ShopType> typeList = JSONUtil.toList(cacheJson, ShopType.class);
            return Result.ok(typeList);
        }

        // 3.缓存未命中（cacheJson == null），查DB
        List<ShopType> typeList = queryTypeListFromDb();

        // 4.DB为空 → 缓存空标记（2分钟），防止缓存穿透
        if (typeList == null || typeList.isEmpty()) {
            try {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, EMPTY_LIST_MARK,
                        CACHE_NULL_TTL, TimeUnit.MINUTES);
            } catch (Exception ignored) {}
            return Result.ok(Collections.emptyList());
        }

        // 5.DB有数据 → 写入Redis，TTL加随机抖动防止缓存雪崩
        try {
            long ttl = CACHE_SHOP_TYPE_TTL + ThreadLocalRandom.current().nextLong(5);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList),
                    ttl, TimeUnit.MINUTES);
        } catch (Exception ignored) {}

        return Result.ok(typeList);
    }

    /**
     * 直接从DB查询商品类型（按sort升序）
     */
    private List<ShopType> queryTypeListFromDb() {
        return query().orderByAsc("sort").list();
    }
}
