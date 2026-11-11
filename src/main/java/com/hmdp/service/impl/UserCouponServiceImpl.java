package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.*;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.entity.CouponUseLog;
import com.hmdp.entity.UserCoupon;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.CouponTemplateMapper;
import com.hmdp.mapper.CouponUseLogMapper;
import com.hmdp.mapper.UserCouponMapper;
import com.hmdp.service.IMemberService;
import com.hmdp.service.CouponIndexService;
import com.hmdp.service.IUserCouponService;
import com.hmdp.utils.CouponCalc;
import com.hmdp.utils.CouponConst;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 领券链路实现（施工手册 Day 3）。
 * <p>
 * 整个券体系里唯一有并发风险的一段，核心就一句话：
 * Redis 只负责「减少打库」，正确性完全由数据库的原子 UPDATE 保证。
 */
@Slf4j
@Service
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    @Resource
    private CouponTemplateMapper templateMapper;

    // 注意：ServiceImpl 内部那个字段叫 baseMapper，不是 userCouponMapper。
    // 这里显式再注入一次，是为了让代码读起来能一眼看出操作的是哪张表，
    // 否则满屏 baseMapper 在多个 Service 里容易看错。
    @Resource
    private UserCouponMapper userCouponMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CouponUseLogMapper useLogMapper;

    @Resource
    private CouponIndexService couponIndex;

    @Resource
    private IMemberService memberService;

    /**
     * 临期扫描的向前看天数上限。
     * <p>
     * 取 7 不是业务拍出来的：临期提醒的语义是「这几天要过期了」，
     * 超过一周就不叫临期，叫「券包概览」，那该走个人券列表而不是这个运营接口。
     * 另一个作用是防止有人拿 {@code days=3650} 把整个索引扫一遍。
     */
    private static final int EXPIRE_SCAN_MAX_DAYS = 7;

    /** 单次返回条数上限。运营界面一页放不下 100 条，更大的值只会拖长接口耗时 */
    private static final int EXPIRE_SCAN_MAX_LIMIT = 100;

    /**
     * 领取一张券。
     * <p>
     * 顺序是刻意设计的，三个「为什么」：
     * <p>
     * ① 为什么 Redis 计数放在扣库存之前：
     *    反过来的话，一张只剩 0 张的券被 50 个人抢，库存那步全部失败返回，
     *    但计数已经打满，这些人第二天领别的券时会被「已达上限」误伤。
     * <p>
     * ② 为什么还要数据库兜底查一次限领：
     *    Redis 是缓存，可能丢、可能过期、可能被手动清掉。
     *    丢了以后限领就形同虚设，所以真正的判据在 tb_user_coupon 的行数上。
     * <p>
     * ③ 为什么插库失败要同时还库存 + 回滚计数：
     *    只还一个就会出现「库存少了一张但没人拿到券」或者
     *    「券拿到了但库存没扣」的错账，对账 SQL 一跑就露馅。
     */
    @Override
    @Transactional
    public Result claim(Long templateId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 1. 模板必须读库，不读缓存（缓存有 30 分钟旧值，拿它判断门槛会资损）
        CouponTemplate t = templateMapper.selectById(templateId);
        if (t == null || t.getStatus() == null || t.getStatus() != CouponConst.TPL_ON) {
            return Result.fail("该券不存在或已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!isValidNow(t, now)) {
            return Result.fail("该券不在领取时间内");
        }
        // 付费购买型走秒杀链路，不走这里
        if (t.getAcquireMode() != null && t.getAcquireMode() == CouponConst.ACQUIRE_BUY) {
            return Result.fail("该券需在秒杀频道购买");
        }

        // 2. 会员门槛（Day 8 接入）
        //    注意这里查的是「用户当前等级」，不是「用户的成长值」：
        //    等级已经是被成长值算过的结果，直接用 level 比较是 O(1)，
        //    如果在这里拿成长值去比对各档门槛，等于把升级逻辑又实现了一遍，
        //    两处规则迟早会漂移（比如运营调了门槛，只改了升级那边）。
        //
        //    memberLevel 为 null 或 0 表示不限等级，绝大多数券是这种。
        //    levelOf 查不到会员行时返回 0，所以新用户在这里不会报错，只是被挡在金卡券外面。
        Integer needLevel = t.getMemberLevel();
        if (needLevel != null && needLevel > 0) {
            int myLevel = memberService.levelOf(user.getId());
            if (myLevel < needLevel) {
                return Result.fail("该券仅限"
                        + memberService.levelName(needLevel) + "及以上领取");
            }
        }

        // 3. 数据库兜底：限领判据落在已发券行数上
        int limit = (t.getPerUserLimit() == null) ? 1 : t.getPerUserLimit();
        long got = userCouponMapper.selectCount(new QueryWrapper<UserCoupon>()
                .eq("user_id", user.getId())
                .eq("template_id", templateId));
        if (got >= limit) {
            return Result.fail("你已达到领取上限");
        }

        // 4. Redis 预检：挡掉绝大部分重复点击，减少打库
        String countKey = RedisConstants.COUPON_USER_COUNT_KEY + templateId + ":" + user.getId();
        Long cnt = null;
        try {
            cnt = stringRedisTemplate.opsForValue().increment(countKey);
            if (cnt != null && cnt == 1L) {
                stringRedisTemplate.expire(countKey, 7, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            // Redis 故障不能阻断领券，降级为只靠数据库兜底
            log.error("领券计数 Redis 异常，降级走数据库判据，templateId={}, userId={}",
                    templateId, user.getId(), e);
        }
        if (cnt != null && cnt > limit) {
            rollbackCount(countKey);
            return Result.fail("你已达到领取上限");
        }

        // 5. Redis 库存预检：把「券已抢光」的请求挡在库外，减少无效打库。
        //    这一步只是快路径，不是判据——真正决定成败的是下面那条条件 UPDATE。
        //    不限量券（total_stock = -1）跳过预检，理由同下。
        boolean limited = t.getTotalStock() != null && t.getTotalStock() != -1;
        if (limited && !preDeductStock(templateId, t.getRemainStock())) {
            rollbackCount(countKey);
            return Result.fail("来晚了，券已抢光");
        }

        // 6. 数据库扣库存：影响行数是「有没有扣到」的唯一判据。
        //    不限量券（total_stock = -1）不参与扣减——否则 remain_stock 会被
        //    从 -1 扣成 -2、-3，数值失去意义。
        int rows = 1;
        if (limited) {
            rows = templateMapper.deductStock(templateId);
        }
        if (rows == 0) {
            // 预检已经扣掉的那 1 个必须还回去。不还的话 Redis 会比 DB 少，
            // 越积越多，最后变成「DB 有货但所有人都领不到」的误杀。
            rollbackStock(templateId);
            rollbackCount(countKey);
            return Result.fail("来晚了，券已抢光");
        }

        // 7. 插入用户券，唯一索引兜底幂等
        try {
            UserCoupon uc = buildUserCoupon(t, user.getId(), now);
            userCouponMapper.insert(uc);
            // Day 10：挂进临期索引。放在 insert 之后，因为 ZSet 的 member 用的是自增主键，
            // 插入前这个 id 还不存在。这里失败不影响领券（索引是副本，查询会降级走 SQL）。
            couponIndex.addExpire(uc.getId(), uc.getExpireTime());
            return Result.ok(uc.getId());
        } catch (DuplicateKeyException e) {
            // 三个补偿动作缺一不可：DB 库存、Redis 库存、限领计数
            // （不限量券的 increaseStock 会被 SQL 的 remain_stock != -1 自动跳过）
            templateMapper.increaseStock(templateId);
            if (limited) {
                rollbackStock(templateId);
            }
            rollbackCount(countKey);
            return Result.fail("你已领取过该券");
        }
    }

    /**
     * 我的券列表。
     * <p>
     * userId 从 UserHolder 取而不是入参传——传进来的 id 可以被篡改，
 * 那就是「改个参数看别人券包」的越权漏洞。
     */
    @Override
    public Result myList(Integer status, int page, int size) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        IPage<UserCoupon> p = new Page<>(page, size);
        QueryWrapper<UserCoupon> w = new QueryWrapper<UserCoupon>()
                .eq("user_id", user.getId())
                .orderByDesc("receive_time");
        // status 语义在这里纠正一次：
        // 表里 2(已过期) 这个值没有任何定时任务会去写，直接 eq("status", 2) 永远查不到数据。
        // 所以「已过期」只能按 expire_time 现算；「未使用」也要排除已过期的，
        // 否则同一张券会同时出现在「未使用」和「已过期」两个 Tab 里。
        LocalDateTime queryNow = LocalDateTime.now();
        if (status == null) {
            // 全部，不额外过滤
        } else if (status == CouponConst.UC_EXPIRED) {
            w.eq("status", CouponConst.UC_UNUSED).lt("expire_time", queryNow);
        } else if (status == CouponConst.UC_UNUSED) {
            w.eq("status", CouponConst.UC_UNUSED).gt("expire_time", queryNow);
        } else {
            w.eq("status", status);
        }
        userCouponMapper.selectPage(p, w);
        if(p.getRecords().isEmpty()){
            return Result.ok(new Page<UserCouponVO>(p.getCurrent(), p.getSize(), p.getTotal()));
        }
        Set<Long> templateIds = new HashSet<>();
        for (UserCoupon uc : p.getRecords()) {
            templateIds.add(uc.getTemplateId());
        }
        List<CouponTemplate> templates = templateMapper.selectBatchIds(templateIds);
        Map<Long, CouponTemplate> tplMap = new HashMap<>();
        for (CouponTemplate t : templates) {
            tplMap.put(t.getId(), t);
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserCouponVO> vos = new ArrayList<>();
        for (UserCoupon uc : p.getRecords()) {
            CouponTemplate t = tplMap.get(uc.getTemplateId());
            if(t == null){
                continue;
            }
            UserCouponVO vo = new UserCouponVO();
            vo.setId(uc.getId());
            vo.setName(t.getName());
            vo.setType(t.getType());
            vo.setDiscountType(t.getDiscountType());
            vo.setThresholdYuan(t.getThresholdAmount() / 100);
            vo.setDiscountYuan(t.getDiscountAmount() / 100);
            vo.setDiscountRate(t.getDiscountRate());
            vo.setMaxDiscountYuan(t.getMaxDiscount()/100);
            vo.setShopId(uc.getShopId());
            vo.setStatus(uc.getStatus());
            vo.setReceiveTime(uc.getReceiveTime());
            vo.setExpireTime(uc.getExpireTime());
            vo.setStackable(t.getStackable() != null && t.getStackable() == 1);
            vo.setExpired(uc.getExpireTime().isBefore(now));
            vos.add(vo);
        }
        Page<UserCouponVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(vos);
        return Result.ok(voPage);
    }

    /**
     * 构造用户券实例。
     * <p>
     * 两个字段值得注意：
     * - couponType / shopId 是从模板<strong>冗余</strong>过来的，
     *   这样「我的券」列表页不用每次 join 模板表，代价是模板改了这里不会跟着变——
     *   对券来说可以接受，因为券一旦发出规则就应该冻结。
     * - expireTime 在领取这一刻就算死，之后所有判断只读这个字段，
     *   不用再回头看 validType 是固定区间还是领取后 N 天。
     */
    private UserCoupon buildUserCoupon(CouponTemplate t, Long userId, LocalDateTime now) {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setTemplateId(t.getId());
        uc.setCouponType(t.getType());
        uc.setShopId(t.getShopId());
        uc.setSource(t.getAcquireMode() != null && t.getAcquireMode() == CouponConst.ACQUIRE_CLAIM
                ? CouponConst.SRC_CLAIM : CouponConst.SRC_SYSTEM);
        uc.setStatus(CouponConst.UC_UNUSED);
        uc.setReceiveTime(now);
        uc.setExpireTime(t.getValidType() != null && t.getValidType() == CouponConst.VALID_FIXED
                ? t.getValidEnd()
                : now.plusDays(t.getValidDays() == null ? 0 : t.getValidDays()));
        // 只有会员月度券才写发放周期，普通券保持 null
        uc.setGrantPeriod(null);
        return uc;
    }

    /** 模板当前是否处在可领取的时间窗口内 */
    private boolean isValidNow(CouponTemplate t, LocalDateTime now) {
        if (t.getValidType() == null || t.getValidType() != CouponConst.VALID_FIXED) {
            return true;
        }
        if (t.getValidStart() != null && now.isBefore(t.getValidStart())) {
            return false;
        }
        return t.getValidEnd() == null || !now.isAfter(t.getValidEnd());
    }

    /**
     * 回滚 Redis 计数。
     * Redis 不参与 Spring 事务，没有自动回滚，必须手动补偿；
     * 这里再包一层 try 是防止「补偿失败」把正常的失败提示也搞没了。
     */
    private void rollbackCount(String countKey) {
        try {
            stringRedisTemplate.opsForValue().decrement(countKey);
        } catch (Exception ignored) {
            // 补偿失败可接受，靠数据库兜底和对账修复
        }
    }

    /**
     * Redis 库存预检：确保键存在后原子减 1。
     *
     * @return true = 允许继续（含 Redis 故障时的降级放行）；false = 确定已抢光
     * <p>
     * 三个细节都是有意的：
     * 1. 回填用 setIfAbsent 不用 set —— 并发首次请求时，set 会把别人刚扣掉的值
     *    覆盖回原值，等于白扣；
     * 2. 键缺失时必须回填而不是当成 0 —— 当成 0 会让所有券被判成已抢光，
     *    一次 Redis 重启就能把整个领券功能打死；
     * 3. 减到负数要立刻加回来 —— DECR 对不存在的键会先置 0 再减，得到 -1，
     *    不加回来这个键就永久卡在负数，之后每次预检都失败。
     */
    private boolean preDeductStock(Long templateId, Integer dbRemain) {
        if (dbRemain == null) {
            return true;   // 库存未知，交给数据库判断
        }
        String key = RedisConstants.COUPON_STOCK_KEY + templateId;
        try {
            stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(dbRemain));
            Long left = stringRedisTemplate.opsForValue().decrement(key);
            if (left != null && left < 0) {
                stringRedisTemplate.opsForValue().increment(key);
                return false;
            }
            return true;
        } catch (Exception e) {
            // Redis 故障不能阻断领券，降级为只靠 DB 判据
            log.error("券库存预检异常，降级查库，templateId={}", templateId, e);
            return true;
        }
    }

    /** 预检扣掉的库存还回去。失败可接受——下次启动的校准会把副本刷回 DB 的值 */
    private void rollbackStock(Long templateId) {
        try {
            stringRedisTemplate.opsForValue()
                    .increment(RedisConstants.COUPON_STOCK_KEY + templateId);
        } catch (Exception ignored) {
        }
    }
    @Override
    public Result available(Long shopId, Integer orderAmount) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (shopId == null || orderAmount == null || orderAmount < 0) {
            return Result.fail("参数不合法");
        }

        CouponCalcDTO dto = new CouponCalcDTO();
        dto.setUsableList(new ArrayList<>());
        dto.setUnusableList(new ArrayList<>());
        dto.setBestCouponIds(new ArrayList<>());

        // 1. 我名下未使用的券
        List<UserCoupon> mine = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                .eq("user_id", user.getId())
                .eq("status", CouponConst.UC_UNUSED));

        if (mine.isEmpty()) {
            return Result.ok(fillAmount(dto, orderAmount, orderAmount));
        }

        // 2. 批量查模板，避免 N+1
        Set<Long> tplIds = new HashSet<>();
        for (UserCoupon uc : mine) {
            tplIds.add(uc.getTemplateId());
        }
        Map<Long, CouponTemplate> tplMap = new HashMap<>();
        for (CouponTemplate t : templateMapper.selectBatchIds(tplIds)) {
            tplMap.put(t.getId(), t);
        }

        // 3. 逐张判断，可用 / 不可用分两堆
        LocalDateTime now = LocalDateTime.now();
        List<CouponCalc.Item> items = new ArrayList<>();
        for (UserCoupon uc : mine) {
            CouponTemplate t = tplMap.get(uc.getTemplateId());
            if (t == null) {
                log.warn("用户券缺少模板，跳过：userCouponId={}, templateId={}",
                        uc.getId(), uc.getTemplateId());
                continue;
            }
            CouponCheck check = CouponCalc.checkOne(uc, t, shopId, orderAmount, now);
            if (check.isUsable()) {
                dto.getUsableList().add(check);
                items.add(toItem(uc, t));
            } else {
                dto.getUnusableList().add(check);
            }
        }

        // 4. 在可用券里算最优组合
        CouponCalc.Result calc = CouponCalc.calc(orderAmount, items);
        dto.setBestCouponIds(calc.getPickedIds());
        return Result.ok(fillAmount(dto, calc.getOriginAmount(), calc.getPayAmount()));
    }

    private CouponCalc.Item toItem(UserCoupon uc, CouponTemplate t) {
        CouponCalc.Item it = new CouponCalc.Item();
        it.setUserCouponId(uc.getId());
        it.setDiscountType(t.getDiscountType());
        it.setThresholdAmount(t.getThresholdAmount());
        it.setDiscountAmount(t.getDiscountAmount());
        it.setDiscountRate(t.getDiscountRate());
        it.setMaxDiscount(t.getMaxDiscount());
        it.setStackable(t.getStackable());
        return it;
    }

    private CouponCalcDTO fillAmount(CouponCalcDTO dto, int originFen, int payFen) {
        dto.setOriginAmountYuan(fen2yuan(originFen));
        dto.setPayAmountYuan(fen2yuan(payFen));
        dto.setSavedAmountYuan(fen2yuan(originFen - payFen));
        return dto;
    }

    private BigDecimal fen2yuan(int fen) {
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // 5.2 下单核销（Day 5 主体）
    // ------------------------------------------------------------------

    /**
     * 下单核销：把一组券标记为已使用，并逐张落核销流水。
     * <p>
     * 这段代码的正确性依赖两个前提，缺一不可：
     * <ol>
     *   <li><b>判断和执行在一条 SQL 里</b>——{@code useOne} 的 WHERE 带着
     *       {@code status=0 AND expire_time>NOW()}，并发下第二个人必然拿到 0 行；</li>
     *   <li><b>异常必须抛出去</b>——只有异常传播到方法边界，Spring 才会回滚。
     *       任何一处 catch 后 {@code return Result.fail(...)}，
     *       都会让「前面几张券已核销、后面失败」变成永久脏数据。</li>
     * </ol>
     * 事务只标在这一个 public 方法上：同类内部调用不会走代理，
     * 拆出去的私有方法即使标了 {@code @Transactional} 也不会生效。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result useCoupons(UseCouponRequest req) {
        // ---------- 1. 参数校验：先把明显不合法的请求挡在库外 ----------
        if (req == null || req.getOrderId() == null || req.getShopId() == null
                || req.getOrderAmount() == null || req.getOrderAmount() <= 0) {
            throw new BizException("参数不合法");
        }
        if (req.getUserCouponIds() == null || req.getUserCouponIds().isEmpty()) {
            throw new BizException("请选择要使用的券");
        }
        // 去重：同一张券传两次的话，第二次核销必然失败并连累整单回滚，不如提前拦掉
        LinkedHashSet<Long> idSet = new LinkedHashSet<>(req.getUserCouponIds());
        if (idSet.size() > CouponConst.MAX_USE_PER_ORDER) {
            throw new BizException("一单最多使用 " + CouponConst.MAX_USE_PER_ORDER + " 张券");
        }
        List<Long> ids = new ArrayList<>(idSet);
        int orderAmount = req.getOrderAmount();

        // ---------- 2. 身份只从 ThreadLocal 取 ----------
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new BizException("请先登录");
        }
        Long userId = user.getId();

        // ---------- 3. 查券：user_id 写进 WHERE，而不是查回来再比对 ----------
        // 查回来再比对，等于先把别人的券读进了内存，
        // 哪天有人顺手 log 一下就是一次信息泄露。
        List<UserCoupon> mine = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                .in("id", ids)
                .eq("user_id", userId));
        if (mine.size() != ids.size()) {
            throw new BizException("券不存在或不属于你");
        }

        // ---------- 4. 批量查模板，避免 N+1 ----------
        Set<Long> tplIds = new HashSet<>();
        for (UserCoupon uc : mine) {
            tplIds.add(uc.getTemplateId());
        }
        Map<Long, CouponTemplate> tplMap = new HashMap<>();
        for (CouponTemplate t : templateMapper.selectBatchIds(tplIds)) {
            tplMap.put(t.getId(), t);
        }

        // ---------- 5. 逐张校验：这里和 available 的差别是「一张不合格就整体失败」 ----------
        LocalDateTime now = LocalDateTime.now();
        List<CouponCalc.Item> items = new ArrayList<>(mine.size());
        for (UserCoupon uc : mine) {
            CouponTemplate t = tplMap.get(uc.getTemplateId());
            if (t == null) {
                throw new BizException("券模板已不存在");
            }
            CouponCheck chk = CouponCalc.checkOne(uc, t, req.getShopId(), orderAmount, now);
            if (!chk.isUsable()) {
                throw new BizException(chk.getReasonText());
            }
            items.add(toItem(uc, t));
        }

        // ---------- 6. 算钱：三阶段固定顺序，得到实际生效的券与各自抵扣 ----------
        CouponCalc.Result calc = CouponCalc.applyWithDetail(orderAmount, items);
        List<Long> pickedIds = calc.getPickedIds();
        if (pickedIds.isEmpty()) {
            throw new BizException("所选券不满足使用条件");
        }

        // ---------- 7. 核销 + 写流水 ----------
        int idx = 0;
        for (Long id : pickedIds) {
            // 影响行数是唯一判据：0 行 = 已被用掉 / 刚好过期 / 不是本人的券
            if (userCouponMapper.useOne(id, userId, req.getOrderId()) != 1) {
                throw new BizException("券已被使用或已过期，请刷新后重试");
            }
            // Day 10：摘掉临期索引。必须用 afterCommit 版本——
            // 本方法是事务，后面任何一张券失败都会整体回滚，
            // 提前摘索引会让「回滚后仍然可用的券」从提醒列表里永久消失。
            couponIndex.removeExpireAfterCommit(id);

            CouponUseLog record = new CouponUseLog();
            record.setUserCouponId(id);
            record.setUserId(userId);
            record.setOrderId(req.getOrderId());
            record.setShopId(req.getShopId());
            record.setOrderAmount(orderAmount);
            record.setDiscountAmount(calc.getEachCut().getOrDefault(id, 0));
            // 实付只写第一行，其余写 0。
            // 每行都写的话，对账 SUM(pay_amount) 会放大成 N 倍。
            record.setPayAmount(idx == 0 ? calc.getPayAmount() : 0);
            // createTime 故意不设：交给表的 DEFAULT CURRENT_TIMESTAMP，
            // 与 use_time 的 NOW() 用同一个时钟源，对账时间线才不会错位。
            useLogMapper.insert(record);
            idx++;
        }

        // ---------- 8. 回显 ----------
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", req.getOrderId());
        data.put("payAmountYuan", fen2yuan(calc.getPayAmount()));
        data.put("savedAmountYuan", fen2yuan(calc.getSavedAmount()));
        data.put("usedCouponIds", pickedIds);
        return Result.ok(data);
    }

    // ------------------------------------------------------------------
    // 5.3 取消订单退券
    // ------------------------------------------------------------------

    /**
     * 取消订单退券：把这笔订单用掉的券恢复成未使用。
     * <p>
     * 顺序是「先查流水 → 逐张退券 → 再删流水」，不能反过来：
     * 先删流水的话，万一下一步退券失败，就永远查不到这笔订单用过哪些券了，
     * 券会卡在「已使用」状态且无从追溯。
     * <p>
     * 另一种写法是拿订单号去 {@code tb_user_coupon} 里按 {@code order_id} 反查，
     * 不用流水表。这里走流水表是因为它记着每张券抵扣了多少，
     * 能顺带算出「这笔订单该退多少优惠」，而券表只有状态没有金额。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result revertCoupons(Long orderId) {
        // ---------- 1. 参数与身份 ----------
        if (orderId == null) {
            throw new BizException("参数不合法");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new BizException("请先登录");
        }
        Long userId = user.getId();

        // ---------- 2. 这笔订单的核销流水（带 user_id，防止退别人的单） ----------
        List<CouponUseLog> logs = useLogMapper.selectList(new QueryWrapper<CouponUseLog>()
                .eq("order_id", orderId)
                .eq("user_id", userId));
        if (logs.isEmpty()) {
            // 也覆盖了「重复调用退券接口」的情况：第一次已经把流水删干净了
            throw new BizException("该订单没有券核销记录");
        }

        // ---------- 3. 逐张退 ----------
        List<Long> revertedIds = new ArrayList<>();
        List<Long> expiredIds = new ArrayList<>();
        List<Long> conflictIds = new ArrayList<>();
        int refundedDiscount = 0;

        for (CouponUseLog record : logs) {
            Long ucId = record.getUserCouponId();
            if (userCouponMapper.revertOne(ucId, userId, orderId) == 1) {
                revertedIds.add(ucId);
                refundedDiscount += record.getDiscountAmount() == null ? 0 : record.getDiscountAmount();
                // Day 10：核销时摘掉的索引，退券时要挂回去。
                // 这里需要 expireTime，所以在循环里补查一次——退券是低频操作（取消订单），
                // 一次多查几条无所谓，比在核销时把过期时间也写进流水表要干净。
                UserCoupon back = userCouponMapper.selectById(ucId);
                if (back != null) {
                    couponIndex.addExpireAfterCommit(back.getId(), back.getExpireTime());
                }
            } else {
                // 退失败只有两种可能，分清楚才能给用户准确提示
                UserCoupon uc = userCouponMapper.selectById(ucId);
                if (uc != null && uc.getExpireTime() != null
                        && uc.getExpireTime().isBefore(LocalDateTime.now())) {
                    expiredIds.add(ucId);      // 券已过期，按规则不退
                } else {
                    conflictIds.add(ucId);     // 状态异常，需要人工介入
                }
            }
        }

        // 一张都没退成：直接报错，用过期这种最可能的原因作提示
        if (revertedIds.isEmpty()) {
            if (!expiredIds.isEmpty()) {
                throw new BizException("券已过期，无法退回");
            }
            throw new BizException("退券失败，券状态异常");
        }

        // ---------- 4. 只删成功退回的券的流水 ----------
        // 未能退回的券必须保留流水：一来它仍是「已使用」状态，流水是唯一凭证；
        // 二来删了流水又没退券，会对账时对不上。
        useLogMapper.delete(new QueryWrapper<CouponUseLog>()
                .in("user_coupon_id", revertedIds)
                .eq("order_id", orderId));

        // ---------- 5. 回显：成功/过期/异常三堆分别给出 ----------
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        data.put("revertedIds", revertedIds);
        data.put("expiredIds", expiredIds);
        data.put("conflictIds", conflictIds);
        data.put("refundedDiscountYuan", fen2yuan(refundedDiscount));
        return Result.ok(data);
    }

    /**
     * ⚠️ 这个方法留给你实现。
     * <p>
     * 要做的事：从 ZSet {@code coupon:expire:soon} 里取出未来 {@code days} 天内到期的券 id，
     * 回表补齐名称和面额，按「快到期的在前」返回。
     * <p>
     * <strong>动手前先想清楚这四件事</strong>（答案在 docs/Day10-临期券与ZSet区间.md）：
     * <ol>
     *   <li>区间上下界怎么算？score 是<strong>秒</strong>，而 {@code days} 是天。
     *       少乘 86400 会什么都查不到，乘成毫秒会查出全部。
     *       Redis 的区间默认是<strong>闭区间</strong>，想排除边界要用 {@code (} 前缀，
     *       但这里恰好应该用闭区间——正好在临界点过期的券也要提醒。</li>
     *   <li>{@code ZRANGEBYSCORE ... LIMIT offset count} 里 offset / count 用什么？
     *       一次拉太多会把运营界面拖死。</li>
     *   <li>回表之后顺序还在吗？{@code selectBatchIds} 返回的是主键序，
     *       ZSet 的顺序会丢——必须自己还原，否则「最快的在最前」这个契约就废了。</li>
     *   <li>{@link CouponIndexService#addExpire} 是 fire-and-forget 的，
     *       ZSet 里可能残留已核销的券。回表后要不要再筛一遍状态？</li>
     * </ol>
     * <p>
     * <strong>两个边界</strong>：
     * <ul>
     *   <li>{@code days <= 0} 或 {@code limit <= 0} 直接参数错误，
     *       不要静默当成「查全部」——那是最容易被拿来打接口的那种入参；</li>
     *   <li>Redis 抛异常时要<strong>降级查库</strong>，不是返回空列表。
     *       返回空会让运营以为「今天没有临期券」，而真相是 Redis 挂了。</li>
     * </ul>
     * <p>
     * 返回类型是 {@code Result}，data 里放 {@code List<ExpiringCouponVO>}。
     */
    @Override
    public Result listExpiringSoon(int days, int limit) {
        // ---------- 1. 参数闸门 ----------
        // 不做「非法值当默认值」的宽容处理：days=0 若被悄悄当成「查全部」，
        // 这个接口就成了「一条请求扫全表」的入口，而且运营看不出参数写错了。
        if (days <= 0 || days > EXPIRE_SCAN_MAX_DAYS) {
            return Result.fail("days 必须在 1~" + EXPIRE_SCAN_MAX_DAYS + " 之间");
        }
        if (limit <= 0 || limit > EXPIRE_SCAN_MAX_LIMIT) {
            return Result.fail("limit 必须在 1~" + EXPIRE_SCAN_MAX_LIMIT + " 之间");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusDays(days);

        // ---------- 2. 从 ZSet 取候选 id ----------
        // 区间用 [now, until] 闭区间：正好卡在临界秒过期的券也该被提醒，
        // 所以宁可多提醒一条，也不要因为在边界上少算一秒而漏掉。
        // 上下界全部走 CouponIndexService 的静态方法，绝不自己拼 86400——
        // 写入侧用的是 atZone(SystemDefault).toEpochSecond()，
        // 查询侧若改用 currentTimeMillis()/1000，在夏令时时区就会整体错位一小时。
        List<Long> ids;
        try {
            Set<String> members = stringRedisTemplate.opsForZSet().rangeByScore(
                    RedisConstants.COUPON_EXPIRE_ZSET,
                    (double) CouponIndexService.nowSeconds(),
                    CouponIndexService.toScore(until),
                    0L,
                    (long) limit);
            ids = parseCouponIds(members);
        } catch (Exception e) {
            // 只能吞到「取 id」这一步。返回空列表是不行的：
            // 空列表和「今天真的没有临期券」在运营眼里是同一件事，
            // 而真相是 Redis 挂了，那一批提醒就这么漏掉了。
            log.error("临期索引查询失败，降级查库 days={}, limit={}", days, limit, e);
            ids = queryExpiringIdsFromDb(now, until, limit);
        }

        // 3. 空不是错误。区别于上面的降级：这里是真的没有。
        if (ids.isEmpty()) {
            return Result.ok(new ArrayList<ExpiringCouponVO>());
        }

        // 4. 回表组装。顺序、脏数据、模板 join 都在里面处理
        return Result.ok(buildExpiringVOs(ids, now));
    }

    // ------------------------------------------------------------------
    // listExpiringSoon 的辅助方法
    // ------------------------------------------------------------------

    /**
     * ZSet 的 member 转成 id 列表。
     * <p>
     * 这里保留了 {@code Set} 的迭代顺序——Spring Data Redis 返回的是按 score 升序的
     * {@code LinkedHashSet}，也就是「最快到期的在最前面」。这个顺序是全链路唯一的排序依据，
     * 一旦在这里换成 {@code new HashSet<>(members)} 就散了。
     * <p>
     * 解析失败直接跳过：ZSet 是运维能手工 ZADD 的地方，出现非数字 member 说明有人手抖，
     * 不该让整个接口 500。
     */
    private List<Long> parseCouponIds(Set<String> members) {
        List<Long> ids = new ArrayList<>();
        if (members == null || members.isEmpty()) {
            return ids;
        }
        for (String m : members) {
            if (m == null || m.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(m));
            } catch (NumberFormatException e) {
                log.warn("临期索引里存在非法 member，已跳过：{}", m);
            }
        }
        return ids;
    }

    /**
     * 降级路径：Redis 不可用时用 SQL 取同一批券的 id。
     * <p>
     * 条件必须和 ZSet 查询<strong>严格等价</strong>（status=0、now &lt; expire_time &lt;= until、
     * 按 expire_time 升序），否则「有没有 Redis」会算出两个不同的数字，
     * 运营对账时无从判断哪个是对的，比接口直接报错还难查。
     * <p>
     * 只 select id，再交给 {@link #buildExpiringVOs} 复用组装逻辑：
     * 多花一次回表，换来「两条路径的顺序、过滤规则、字段填充永远一致」。
     * 这段代码只有 Redis 挂掉时才会执行，不值得为省一次查询再写第二套组装。
     */
    private List<Long> queryExpiringIdsFromDb(LocalDateTime now, LocalDateTime until, int limit) {
        List<UserCoupon> rows = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                .select("id")
                .eq("status", CouponConst.UC_UNUSED)
                .gt("expire_time", now)
                .le("expire_time", until)
                .orderByAsc("expire_time")
                .last("LIMIT " + limit));
        List<Long> ids = new ArrayList<>(rows.size());
        for (UserCoupon uc : rows) {
            ids.add(uc.getId());
        }
        return ids;
    }

    /**
     * 把有序的 id 列表组装成运营视图。
     * <p>
     * 核心是那个 <strong>「先按 id 建 Map，再按入参顺序遍历」</strong>的写法：
     * {@code selectBatchIds} 底层是 {@code WHERE id IN (...)}，返回的是主键序，
     * ZSet 的顺序到这一步就丢了。如果直接迭代 {@code rows}，
     * 「最快到期的在最前面」这个契约就废了，运营看到的顺序接近随机。
     * <p>
     * 这一坑在 {@code BlogServiceImpl.queryBlogLikes} 已经踩过一次，同一个模式。
     * <p>
     * 三重过滤缺一不可，因为它们对应三种不同的脏数据：
     * <ul>
     *   <li>{@code byId.get(id) == null}：ZSet 里有、库里已经删了；</li>
     *   <li>{@code status != UC_UNUSED}：核销了但 {@code removeExpire} 那次失败了；</li>
     *   <li>{@code expire_time} 已过：过了临界点但 {@code purgeExpired} 还没跑。</li>
     * </ul>
     * 不过滤的后果很具体——提醒用户「你的券快过期了」，用户打开发现早就用掉了。
     * <p>
     * 注意过滤用 {@code continue} 不用 {@code break}：一条脏数据不该让后面的券全部消失。
     * 代价是返回条数可能少于 {@code limit}，这是对的：{@code limit} 的语义是
     * 「最多取几条候选」，不是「保证返回几条」。要凑满得靠翻页，不该在这里补。
     */
    private List<ExpiringCouponVO> buildExpiringVOs(List<Long> orderedIds, LocalDateTime now) {
        List<UserCoupon> rows = userCouponMapper.selectBatchIds(orderedIds);
        Map<Long, UserCoupon> byId = new HashMap<>(Math.max(16, rows.size() * 2));
        for (UserCoupon uc : rows) {
            byId.put(uc.getId(), uc);
        }

        // 模板同样批量查：一次 IN，避免 N+1
        Set<Long> tplIds = new HashSet<>();
        for (UserCoupon uc : rows) {
            if (uc.getTemplateId() != null) {
                tplIds.add(uc.getTemplateId());
            }
        }
        Map<Long, CouponTemplate> tplMap = new HashMap<>(Math.max(16, tplIds.size() * 2));
        if (!tplIds.isEmpty()) {
            for (CouponTemplate t : templateMapper.selectBatchIds(tplIds)) {
                tplMap.put(t.getId(), t);
            }
        }

        List<ExpiringCouponVO> vos = new ArrayList<>(orderedIds.size());
        for (Long id : orderedIds) {
            UserCoupon uc = byId.get(id);
            if (uc == null) {
                continue;
            }
            if (uc.getStatus() == null || uc.getStatus() != CouponConst.UC_UNUSED) {
                continue;
            }
            LocalDateTime expireAt = uc.getExpireTime();
            if (expireAt == null || !expireAt.isAfter(now)) {
                continue;
            }
            CouponTemplate t = tplMap.get(uc.getTemplateId());
            if (t == null) {
                // 模板被硬删了。券还在但没法算出面额，只能丢掉并留痕
                log.warn("临期券缺少模板，已跳过：userCouponId={}, templateId={}",
                        uc.getId(), uc.getTemplateId());
                continue;
            }

            ExpiringCouponVO vo = new ExpiringCouponVO();
            vo.setUserCouponId(uc.getId());
            vo.setUserId(uc.getUserId());
            vo.setTemplateId(uc.getTemplateId());
            vo.setName(t.getName());
            // coupon_type 在发券时从模板冗余下来，优先用券上的值：
            // 模板后来改了类型，已经发出去的券规则不应该跟着变
            vo.setCouponType(uc.getCouponType() != null ? uc.getCouponType() : t.getType());
            vo.setDiscountYuan(fen2yuanInt(t.getDiscountAmount()));
            vo.setThresholdYuan(fen2yuanInt(t.getThresholdAmount()));
            vo.setExpireTime(expireAt);
            vo.setRemainHours(remainHours(now, expireAt));
            vos.add(vo);
        }
        return vos;
    }

    /** 分 → 元。整数除法，和 {@code myList} 里的口径保持一致 */
    private Integer fen2yuanInt(Integer fen) {
        return fen == null ? 0 : fen / 100;
    }

    /**
     * 剩余小时数，向上取整。
     * <p>
     * 不能用 {@code Duration.toHours()}：它向下取整，还剩 59 分钟会算成 0，
     * 前端显示「0 小时后过期」，运营看到只会以为这券已经废了。
     * 向上取整的语义是「还剩不到一小时，也算一小时」，更有行动感。
     */
    private long remainHours(LocalDateTime now, LocalDateTime expireAt) {
        long seconds = Duration.between(now, expireAt).getSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        return (long) Math.ceil(seconds / 3600.0);
    }

}
