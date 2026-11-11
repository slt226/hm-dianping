package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.CouponVO;
import com.hmdp.dto.Result;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.mapper.CouponTemplateMapper;
import com.hmdp.service.ICouponService;
import com.hmdp.utils.CouponConst;
import com.hmdp.utils.RedisConstants;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 券模板业务实现（施工手册 Day 2）。
 */
@Slf4j
@Service
public class CouponServiceImpl implements ICouponService {

    @Resource
    private CouponTemplateMapper templateMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ------------------------------------------------------------------
    // 2.1 建券接口
    // ------------------------------------------------------------------

    /**
     * 创建券模板。
     * <p>
     * 入参格式校验交给 @Valid（Controller 上），这里只做「跨字段语义校验」——
     * 注解能判断 discountRate 是不是正整数，但判断不了
     * 「满减券必须填门槛」「折扣率必须在 1~99」这种字段间的约束。
     * 这类规则必须收在一处，否则三种券各写一遍，改规则时一定会漏。
     */
    @Override
    public Result createTemplate(CouponTemplate t) {
        // 1. 跨字段语义校验：返回 null 表示通过，否则返回错误文案
        String err = checkTemplate(t);
        if (err != null) {
            return Result.fail(err);
        }

        // 2. 库存初始化：总库存即剩余库存（-1 表示不限量，原样透传）
        if (t.getRemainStock() == null) {
            t.setRemainStock(t.getTotalStock());
        }
        t.setStatus(CouponConst.TPL_ON);
        templateMapper.insert(t);

        // 3. 预热库存到 Redis。Redis 挂了不能影响建券，所以包一层 try。
        writeStockToRedis(t.getId(), t.getRemainStock());
        return Result.ok(t.getId());
    }

    // ------------------------------------------------------------------
    // 7.2 库存副本的可重建性
    // ------------------------------------------------------------------

    /**
     * 启动时把 DB 的 remain_stock 全量刷进 Redis。
     * <p>
     * 为什么必须有这一步：Redis 是内存库，重启、flushdb、换实例都会让
     * coupon:stock:* 全部消失。缺了校准，领券预检读到的就是一堆不存在的键。
     * <p>
     * 方向只能是 DB → Redis，绝不能反过来。remain_stock 是扣减的唯一判据，
     * Redis 只是它的一个「丢了随时能重建」的副本。这也决定了这里用覆盖写而不是
     * 增量写——校准的语义就是「让副本向真源看齐」。
     */
    @PostConstruct
    public void syncStockToRedis() {
        try {
            List<CouponTemplate> all = templateMapper.selectList(
                    new QueryWrapper<CouponTemplate>().select("id", "remain_stock"));
            int n = 0;
            for (CouponTemplate t : all) {
                if (t.getRemainStock() == null) {
                    continue;
                }
                writeStockToRedis(t.getId(), t.getRemainStock());
                n++;
            }
            log.info("券库存校准完成，共同步 {} 个模板到 Redis", n);
        } catch (Exception e) {
            // 校准失败不影响启动：领券的预检会降级为直接查库
            log.error("券库存校准失败，本次启动领券预检将降级为查库", e);
        }
    }

    /** 单个模板的库存写入，建券预热与启动校准共用一份，避免两处 set 格式不一致 */
    private void writeStockToRedis(Long templateId, Integer remainStock) {
        if (templateId == null || remainStock == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.COUPON_STOCK_KEY + templateId,
                    String.valueOf(remainStock));
        } catch (Exception e) {
            log.error("券库存写入 Redis 失败，templateId={}", templateId, e);
        }
    }

    /** 券模板的规则校验，集中放这里，别散落到三个券类型里各写一份 */
    private String checkTemplate(CouponTemplate t) {
        if (t.getDiscountType() == null) {
            return "必须指定优惠形式";
        }
        // 满减券：门槛不填或为 0，等于无门槛直减，规则冲突
        if (t.getDiscountType() == CouponConst.DISCOUNT_FULL_CUT
                && (t.getThresholdAmount() == null || t.getThresholdAmount() <= 0)) {
            return "满减券必须设置使用门槛";
        }
        // 折扣券：rate=100 是不打折，rate=0 是白送，都不合法
        if (t.getDiscountType() == CouponConst.DISCOUNT_RATE
                && (t.getDiscountRate() == null
                    || t.getDiscountRate() <= 0 || t.getDiscountRate() >= 100)) {
            return "折扣率必须在 1~99 之间";
        }
        // 无门槛直减券：必须填优惠金额
        if (t.getDiscountType() == CouponConst.DISCOUNT_DIRECT
                && (t.getDiscountAmount() == null || t.getDiscountAmount() <= 0)) {
            return "直减券必须设置优惠金额";
        }
        // 固定区间型：失效时间是唯一能算出「什么时候不能用」的字段，不能为空
        if (t.getValidType() != null && t.getValidType() == CouponConst.VALID_FIXED
                && t.getValidEnd() == null) {
            return "固定区间券必须设置失效时间";
        }
        // 领取后 N 天型：天数必须为正
        if (t.getValidType() != null && t.getValidType() == CouponConst.VALID_AFTER_DAYS
                && (t.getValidDays() == null || t.getValidDays() <= 0)) {
            return "领取后有效天数必须大于 0";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 2.2 券列表：一定要批量
    // ------------------------------------------------------------------

    /**
     * 店铺可领券列表。
     * <p>
     * 为什么强调批量：最直觉的写法是「查到券 → 循环里再去查每张券的补充信息」，
     * 这在只有 3 张券时看不出问题，但 Day 11 的搜索结果页一屏就是 20 家店，
     * 立刻变成 20 次额外查询（N+1）。这里一次查完，后面直接复用。
     */
    @Override
    public Result listAvailable(Long shopId) {
        // 1. 平台通用券 + 本店券，一次 IN 查询搞定
        //    ⚠️ and(w -> ...) 这层嵌套不能漏：
        //    如果写成 .eq(status).in(type).eq(shopId,0).or().eq(shopId,shopId)，
        //    SQL 会变成 WHERE status=? AND type IN (...) AND shop_id=0 OR shop_id=?，
        //    OR 会把整个条件链拆开，把已下架的券也查出来。这是 MP 最常踩的坑。
        List<CouponTemplate> list = templateMapper.selectList(new QueryWrapper<CouponTemplate>()
                .eq("status", CouponConst.TPL_ON)
                .in("type",
                        CouponConst.TYPE_PLATFORM_REDPACK, CouponConst.TYPE_SHOP_COUPON)
                .and(w -> w.eq("shop_id", CouponConst.SHOP_ID_ALL)
                           .or().eq("shop_id", shopId))
                .orderByDesc("discount_amount"));

        // 2. 转 VO：分 → 元
        LocalDateTime now = LocalDateTime.now();
        List<CouponVO> vos = new ArrayList<>(list.size());
        for (CouponTemplate t : list) {
            vos.add(toVO(t, now));
        }
        return Result.ok(vos);
    }

    @Override
    public Result pageCenter(int page, int size) {
        IPage<CouponTemplate> p = new Page<>(page, size);
        templateMapper.selectPage(p, new QueryWrapper<CouponTemplate>()
                .eq("status", CouponConst.TPL_ON)
                .orderByDesc("create_time"));
        // 这里能用 convert 而 myList 不能：查出来的就是模板本身，
        // 不存在「关联数据缺失要跳过某条」的情况，一对一映射天然安全。
        // now 提到循环外，保证同页数据用同一个时间基准判断 expired。
        LocalDateTime now = LocalDateTime.now();
        return Result.ok(p.convert(t -> toVO(t, now)));
    }

    private CouponVO toVO(CouponTemplate t, LocalDateTime now) {
        CouponVO vo = new CouponVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setType(t.getType());
        vo.setDiscountType(t.getDiscountType());
        vo.setShopId(t.getShopId());
        // 分转元：整数除法够用，券金额不会有分以下的精度
        vo.setThresholdYuan(t.getThresholdAmount() / 100);
        vo.setDiscountYuan(t.getDiscountAmount() / 100);
        vo.setDiscountRate(t.getDiscountRate());
        vo.setMaxDiscountYuan(t.getMaxDiscount() / 100);
        vo.setValidDays(t.getValidDays());
        vo.setValidStart(t.getValidStart());
        vo.setValidEnd(t.getValidEnd());
        vo.setPerUserLimit(t.getPerUserLimit());
        vo.setRemainStock(t.getRemainStock());
        // stackable 在库里是 tinyint，null 视为不可叠加
        vo.setStackable(t.getStackable() != null && t.getStackable() == 1);
        vo.setExpired(!isValidNow(t, now));
        return vo;
    }

    /**
     * 判断模板当前是否处在「可领/可用」的时间窗口内。
     * <p>
     * validType=2（领取后 N 天有效）的券，本身没有绝对失效时间窗口，
     * 只要模板上架就能一直领——真正的过期时间写在用户券的 expire_time 上。
     */
    private boolean isValidNow(CouponTemplate t, LocalDateTime now) {
        if (t.getValidType() != CouponConst.VALID_FIXED) {
            return true;
        }
        if (t.getValidStart() != null && now.isBefore(t.getValidStart())) {
            return false;
        }
        return t.getValidEnd() == null || !now.isAfter(t.getValidEnd());
    }

    // ------------------------------------------------------------------
    // 2.3 缓存
    // ------------------------------------------------------------------

    /**
     * 带缓存的模板查询。券模板是典型的读多写少，缓存能挡掉列表页的大部分查询。
     * <p>
     * ⚠️ 两条纪律：
     * 1. 用空串缓存 null，防止不存在的 templateId 被反复打库（缓存穿透）。
     * 2. 只用于展示。领券和核销必须读库——运营把门槛从 100 改成 150 之后，
     *    缓存还有 30 分钟旧值，按旧规则核销就是真金白银的资损。
     */
    public CouponTemplate getTemplateCached(Long id) {
        String key = RedisConstants.COUPON_TEMPLATE_KEY + id;
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return json.isEmpty() ? null : JSONUtil.toBean(json, CouponTemplate.class);
            }
            CouponTemplate t = templateMapper.selectById(id);
            if (t == null) {
                stringRedisTemplate.opsForValue().set(key, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(t),
                    RedisConstants.COUPON_TEMPLATE_TTL, TimeUnit.MINUTES);
            return t;
        } catch (Exception e) {
            // Redis 故障不能拖垮业务：降级为直查数据库
            log.error("券模板缓存异常，降级查库，templateId={}", id, e);
            return templateMapper.selectById(id);
        }
    }

    /** 模板变更（改库存、下架等）时调用：删掉缓存，下次读库重建 */
    public void evictTemplateCache(Long id) {
        try {
            stringRedisTemplate.delete(RedisConstants.COUPON_TEMPLATE_KEY + id);
        } catch (Exception e) {
            log.error("券模板缓存删除失败，templateId={}", id, e);
        }
    }
}
