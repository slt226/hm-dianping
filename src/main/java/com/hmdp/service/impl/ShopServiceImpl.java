package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 方式一：缓存穿透（解决缓存穿透问题）
        // Shop shop = queryWithPassThrough(id);

        // 方式二：互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        // 方式三：逻辑过期解决缓存击穿（需要提前预热热点数据到Redis）
        // Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存穿透解决方案：缓存空值
     * （Redis不可用时降级直接查DB）
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson;
        try {
            // 1.从redis查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("queryWithPassThrough: Redis不可用，降级直查DB | id={} | err={}", id, e.toString());
            return getById(id);
        }
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值（缓存空值 "" ）
        if (shopJson != null) {
            // shopJson="" 说明是缓存的空值，直接返回null，避免穿透到DB
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        if (shop == null) {
            // 将空值写入redis，解决缓存穿透问题，过期时间设置短一点（2分钟）
            try {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            } catch (Exception ignored) {}
            return null;
        }
        // 6.存在，写入redis，添加随机过期时间防止缓存雪崩
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(5), TimeUnit.MINUTES);
        } catch (Exception ignored) {}
        // 7.返回
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     * （Redis不可用时降级直查DB；抢锁失败/异常时也不返回失败）
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson;
        try {
            // 1.从redis查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("queryWithMutex: Redis不可用，降级直查DB | id={} | err={}");
            return getById(id);
        }
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            return null;
        }
        // 4.实现缓存重建，解决缓存击穿
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        boolean locked = false;
        try {
            try {
                locked = tryLock(lockKey);
            } catch (Exception e) {
                log.warn("queryWithMutex: 抢锁异常，降级直查DB | id={} | err={}", id, e.toString());
                return getById(id);
            }
            // 4.2.判断是否获取成功
            if (!locked) {
                // 4.3.获取锁失败，休眠并重试（有限次，避免无限递归栈溢出）
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4.获取锁成功，再次检查redis缓存是否存在（doubleCheck）
            try {
                shopJson = stringRedisTemplate.opsForValue().get(key);
            } catch (Exception ignored) {}
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 4.5.根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时，让测试效果更明显
            Thread.sleep(200);
            // 5.不存在，返回错误
            if (shop == null) {
                // 将空值写入redis，解决缓存穿透
                try {
                    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                } catch (Exception ignored) {}
                return null;
            }
            // 6.存在，写入redis，添加随机过期时间防止缓存雪崩
            try {
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                        CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(5), TimeUnit.MINUTES);
            } catch (Exception ignored) {}
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            if (locked) {
                try { unlock(lockKey); } catch (Exception ignored) {}
            }
        }
        // 8.返回
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿（热点商品使用，需要提前将数据预热到Redis）
     * （Redis不可用时降级直查DB）
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson;
        try {
            // 1.从redis查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("queryWithLogicalExpire: Redis不可用，降级直查DB | id={} | err={}", id, e.toString());
            return getById(id);
        }
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中，直接返回null（逻辑过期方案是热点数据预热，不存在就返回null）
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock;
        try {
            isLock = tryLock(lockKey);
        } catch (Exception e) {
            log.warn("queryWithLogicalExpire: 抢锁异常，直接返回旧数据 | id={} | err={}", id, e.toString());
            return shop;
        }
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3.获取锁成功后，再次检查缓存是否过期（doubleCheck）
            try {
                shopJson = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(shopJson)) {
                    RedisData doubleCheck = JSONUtil.toBean(shopJson, RedisData.class);
                    if (doubleCheck.getExpireTime().isAfter(LocalDateTime.now())) {
                        return JSONUtil.toBean((JSONObject) doubleCheck.getData(), Shop.class);
                    }
                }
            } catch (Exception ignored) {}
            // 6.4.开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    log.warn("queryWithLogicalExpire: 异步重建缓存异常 | id={} | err={}", id, e.toString());
                } finally {
                    try { unlock(lockKey); } catch (Exception ignored) {}
                }
            });
        }
        // 6.5.返回过期的商铺信息（无论是否获取到锁，都直接返回旧数据）
        return shop;
    }

    /**
     * 尝试获取分布式锁
     * 使用 SETNX 实现：key 不存在才设置成功，返回 true 表示获取到锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 预热热点数据到Redis（逻辑过期方案）
     * @param id 店铺id
     * @param expireSeconds 逻辑过期时间（秒）
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis（注意：这里不设置物理过期时间，依靠逻辑过期来判断）
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 应用启动时，把所有商铺的经纬度按类型写入 Redis GEO（shop:geo:{typeId}）。
     * 这样“附近商铺”功能才能基于 GEO 做距离检索。
     */
    @PostConstruct
    public void loadShopData() {
        try {
            // 1.查询所有店铺（带经纬度的）
            List<Shop> list = query().list();
            if (list == null || list.isEmpty()) {
                return;
            }
            // 2.按 typeId 分组（跳过没有经纬度的脏数据）
            Map<Long, List<Shop>> map = list.stream()
                    .filter(s -> s.getX() != null && s.getY() != null)
                    .collect(Collectors.groupingBy(Shop::getTypeId));
            // 3.批量写入 Redis GEO
            for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
                Long typeId = entry.getKey();
                List<Shop> shops = entry.getValue();
                String key = SHOP_GEO_KEY + typeId;
                // 先清空旧数据，避免重启后叠加脏数据
                try {
                    stringRedisTemplate.delete(key);
                } catch (Exception ignored) {}
                List<GeoLocation<String>> locations = new ArrayList<>(shops.size());
                for (Shop shop : shops) {
                    locations.add(new GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())
                    ));
                }
                stringRedisTemplate.opsForGeo().add(key, locations);
            }
            log.info("商铺 GEO 数据加载完成，共 {} 个类型", map.size());
        } catch (Exception e) {
            // GEO 加载失败不能影响应用启动，记日志即可（附近功能暂时不可用）
            log.warn("loadShopData: GEO 数据加载失败（附近功能暂不可用） | err={}", e.toString());
        }
    }

    /**
     * 按类型分页查询商铺。
     * 当传入坐标(x,y)时，使用 Redis GEO 做“附近”检索，按距离排序并回填 distance；
     * 不传坐标时，退化为按类型直接分页（支持 comments / score 排序）。
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, String sortBy, Double x, Double y) {
        // 1.无需坐标（非“附近”场景）：直接按类型分页
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .orderByDesc(StrUtil.isNotBlank(sortBy), sortBy)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2.GEO 附近检索参数：默认 5 公里半径
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        Circle circle = new Circle(new Point(x, y), new Distance(5000));
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                .newGeoRadiusArgs()
                .includeDistance()
                .limit(end);
        GeoResults<GeoLocation<String>> results;
        try {
            results = stringRedisTemplate.opsForGeo().radius(key, circle, args);
        } catch (Exception e) {
            log.warn("queryShopByType: GEO 检索失败，降级为类型分页 | typeId={} | err={}", typeId, e.toString());
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 3.解析 GEO 结果
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 已无更多数据
            return Result.ok(Collections.emptyList());
        }
        // 4.截取本页的 id，并记录每个店铺到用户的距离
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            distanceMap.put(shopIdStr, result.getDistance());
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 5.按 GEO 返回的距离顺序查询店铺（保持 ORDER BY FIELD 的排序）
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 6.回填距离字段（单位：米）
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        // 7.人气/评分排序：在“附近”结果基础上再按字段排序
        if (StrUtil.isNotBlank(sortBy)) {
            shops.sort((s1, s2) -> {
                if ("comments".equals(sortBy)) {
                    return Integer.compare(s2.getComments(), s1.getComments());
                } else if ("score".equals(sortBy)) {
                    return Integer.compare(s2.getScore(), s1.getScore());
                }
                return 0;
            });
        }
        return Result.ok(shops);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空!");
        }
        // 1.先更新数据库
        updateById(shop);
        // 2.再删除缓存（保证一致性：先DB后缓存，即使删除失败也不会造成脏读）
        //    Redis不可用时不能让更新接口整体失败（否则DB回滚更严重），降级为仅记日志
        try {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        } catch (Exception e) {
            log.warn("update: Redis不可用，跳过缓存删除（DB已更新成功） | id={} | err={}", id, e.toString());
        }
        // 3.若坐标有更新，同步维护 GEO（保证“附近”检索结果准确）
        if (shop.getX() != null && shop.getY() != null) {
            try {
                String key = SHOP_GEO_KEY + shop.getTypeId();
                stringRedisTemplate.opsForGeo().add(key,
                        new GeoLocation<>(shop.getId().toString(),
                                new Point(shop.getX(), shop.getY())));
            } catch (Exception e) {
                log.warn("update: GEO 坐标同步失败（不影响主流程） | id={} | err={}", id, e.toString());
            }
        }
        return Result.ok();
    }
}
