package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.*;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.CouponTemplateMapper;
import com.hmdp.mapper.MemberMapper;
import com.hmdp.mapper.UserCouponMapper;
import com.hmdp.service.CouponIndexService;
import com.hmdp.service.IMemberCouponGrantService;
import com.hmdp.service.IMemberService;
import com.hmdp.utils.CouponConst;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会员月度券发放（施工手册 Day 9）。
 * <p>
 * 本类里只有 {@link #grantToUser} 一个是空着的，其余都是给你用的零件。
 * <p>
 * 为什么方法和 Day 8 的 addGrowth 长得这么像：两件事在结构上是同一道题——
 * 都是「用数据库的唯一索引当幂等判据」，区别只在幂等键由谁构造。
 * Day 8 的 bizId 来自请求参数，Day 9 的 period 必须服务端自己算。
 */
@Slf4j
@Service
public class MemberCouponGrantServiceImpl implements IMemberCouponGrantService {

    /**
     * 周期键格式，固定 7 位。
     * <p>
     * 必须补零到两位（{@code M} 而不是 {@code MM}），否则 1 月会变成 {@code 2026-1}：
     * 长度 6，和 {@code char(7)} 对不上，而且「2026-1」和「2026-01」会被当成两个不同的周期，
     * 唯一索引就形同虚设——同一个月能发两次。
     * <p>
     * 也<strong>不能</strong>写成 {@code YYYY-MM}。{@code Y} 是「周所在年」，
     * 2026-01-01 属于 2025 年的最后一周，会被格式化成 {@code 2025-01}，
     * 直接跨年错位。这个坑每年 1 月准时出现一次。
     */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Resource
    private MemberMapper memberMapper;

    @Resource
    private CouponTemplateMapper templateMapper;

    @Resource
    private UserCouponMapper userCouponMapper;

    @Resource
    private IMemberService memberService;

    @Resource
    private CouponIndexService couponIndex;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String buildPeriod(LocalDate date) {
        return date.format(PERIOD_FORMAT);
    }

    /**
     * ⚠️ 这个方法留给你实现。
     * <p>
     * 要做的事：给一个人、一个等级、一个周期，把 {@link #pickTemplates} 选出来的模板
     * 逐个变成他名下的券。
     * <p>
     * <strong>动手前先回答这四个问题</strong>（答案在 docs/Day9-会员券幂等发放.md）：
     * <ol>
     *   <li>{@code source} 字段填 {@link CouponConst} 里的哪个常量？
     *       {@code SRC_SYSTEM} 和 {@code SRC_MEMBER} 差在哪，为什么这里不能用前者？</li>
     *   <li>{@code expireTime} 怎么算？模板的 {@code valid_type=2} 意味着什么？
     *       算出来的这个时间之后还允许改吗？</li>
     *   <li>{@code grantPeriod} 如果忘了填（保持 null），会发生什么？
     *       这个后果比「报错」严重得多，因为它是静默的。</li>
     *   <li>返回 0 的时候，是「已经发过」还是「插入被 MySQL 忽略了」？怎么区分？</li>
     * </ol>
     * <p>
     * <strong>三个不要</strong>：
     * <ul>
     *   <li>不要动 {@code remain_stock}。会员券的 {@code total_stock = -1} 表示不限量，
     *       扣它会变成 -2、-3，Day 5 的库存守恒对账 SQL 立刻报警。</li>
     *   <li>不要先 {@code selectCount} 再插。两个线程会同时查到 0，
     *       然后都去插——判据必须交给唯一索引。</li>
     *   <li>不要 catch {@code DuplicateKeyException}。这个类里不该出现这个异常，
     *       如果你见到它，说明用错了 mapper 方法。</li>
     * </ul>
     * <p>
     * <strong>事务边界加在这一层，不是批量那一层。</strong>
     * 批量发 500 个人如果共用一个事务，第 480 个人失败会把前面 479 个一起回滚——
     * 明明发成功的券全部消失，而且没人知道发生了什么。
     * 更糟的是这 500 条 INSERT 会持有行锁直到最后一条，中间任何慢查询都在放大锁等待。
     * <p>
     * 切成「一个人一个事务」后：失败的单独重试，成功的落袋为安。
     * 代价是失去「整批原子性」——但发券这件事本来就不需要整批原子，
     * 它需要的是<strong>可重入</strong>：重跑一遍，发过的会返回 0，没发的补上。
     * <p>
     * 注意：这个注解能生效的前提是<strong>被别的 Bean 跨类调用</strong>。
     * 如果批量方法写在本类里并直接 {@code this.grantToUser(...)}，
     * Spring 代理被绕过，事务完全不生效——而且不报错，
     * 你只能从上线的脏数据里发现它。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantToUser(Long userId, int level, String period) {
        List<CouponTemplate> tpls=pickTemplates(level);
        int granted=0;
        LocalDateTime now = LocalDateTime.now();
        for(CouponTemplate tpl:tpls) {
            UserCoupon uc=new UserCoupon();
            uc.setUserId(userId);
            uc.setTemplateId(tpl.getId());
            uc.setCouponType(tpl.getType());
            uc.setShopId(tpl.getShopId());
            uc.setSource(CouponConst.SRC_MEMBER);
            uc.setExpireTime(tpl.getValidType() != null && tpl.getValidType() == CouponConst.VALID_FIXED
                    ? tpl.getValidEnd()
                    : now.plusDays(tpl.getValidDays() == null ? 0 : tpl.getValidDays()));
            uc.setGrantPeriod(period);
            int n = userCouponMapper.insertIgnoreGrant(uc);
            granted += n;
            if (n == 1) {
                // Day 10：挂两个索引。只有真的插进去了（返回 1）才挂，
                // 被唯一索引挡下时券本来就已经在索引里，不需要重复写。
                //
                // 为什么这里要回查一次 id：INSERT IGNORE 在「被忽略」时返回 0，
                // MyBatis 的 useGeneratedKeys 在这种情况下拿不到自增主键，
                // 所以 uc.getId() 是 null。临期索引的 member 必须是券 id，
                // 只能按 (user_id, template_id, grant_period) 这把唯一键再查一次。
                UserCoupon saved = userCouponMapper.selectOne(new QueryWrapper<UserCoupon>()
                        .select("id", "expire_time")
                        .eq("user_id", userId)
                        .eq("template_id", tpl.getId())
                        .eq("grant_period", period));
                if (saved != null) {
                    couponIndex.addExpire(saved.getId(), saved.getExpireTime());
                }
                couponIndex.addGrantPeriod(period, userId, tpl.getId());
            }
        }
        return granted;
    }

    @Override
    public List<Member> listGrantableMembers() {        QueryWrapper<Member> qw = new QueryWrapper<>();
        qw.select("user_id", "level").gt("level", 0);
        return memberMapper.selectList(qw);
    }

    @Override
    public List<CouponTemplate> pickTemplates(int level) {
        if (level <= 0) {
            return Collections.emptyList();
        }
        int cap = monthlyCouponCnt(level);
        if (cap <= 0) {
            return Collections.emptyList();
        }

        List<CouponTemplate> candidates = templateMapper.selectList(
                new QueryWrapper<CouponTemplate>()
                        .eq("type", CouponConst.TYPE_MEMBER_REDPACK)
                        .eq("status", CouponConst.TPL_ON)
                        // member_level 是「最低等级」，0 表示不限，所以用 <= 而不是 =
                        .le("member_level", level)
                        .orderByAsc("id"));

        LocalDateTime now = LocalDateTime.now();
        List<CouponTemplate> picked = new ArrayList<>(cap);
        for (CouponTemplate t : candidates) {
            // 固定区间的模板可能已经过了失效时间，这种不能再发出去
            if (t.getValidType() != null && t.getValidType() == CouponConst.VALID_FIXED
                    && t.getValidEnd() != null && now.isAfter(t.getValidEnd())) {
                continue;
            }
            picked.add(t);
            if (picked.size() >= cap) {
                break;
            }
        }
        return picked;
    }

    /**
     * 按周期区间拉发放明细（Day 10，{@code ZRANGEBYLEX}）。
     * <p>
     * 三个细节决定了这段代码对不对：
     * <ol>
     *   <li><strong>上下界要带上分隔符</strong>：member 是 {@code 2026-01#1016#5}，
     *       如果下界只写 {@code 2026-01}，字典序上 {@code 2026-01} &lt; {@code 2026-01#...}
     *       仍然成立，看似没问题；但上界写成 {@code 2026-04}（闭）就会漏掉
     *       {@code 2026-03#...} 之后、{@code 2026-04} 之前的所有成员。
     *       统一在边界上补 {@code #}，区间语义才干净。</li>
     *   <li><strong>上界必须是开区间</strong>：用户传 {@code to=2026-04} 的语义是
     *       「到 3 月底为止」，用闭区间会把 4 月的记录也算进来。</li>
     *   <li><strong>LIMIT 是必须的</strong>：不加的话一次会把整个区间的成员全拉回来，
     *       这个 ZSet 是无上限增长的。</li>
     * </ol>
     */
    @Override
    public List<String> listGrantRecords(String fromPeriod, String toPeriod, int limit) {
        int cap = (limit <= 0 || limit > 500) ? 50 : limit;
        String from = (fromPeriod == null || fromPeriod.isEmpty())
                ? buildPeriod(LocalDate.now().minusMonths(5))
                : fromPeriod;
        String to = (toPeriod == null || toPeriod.isEmpty())
                ? buildPeriod(LocalDate.now().plusMonths(1))
                : toPeriod;
        if (from.compareTo(to) >= 0) {
            throw new BizException("起始周期必须早于结束周期");
        }

        RedisZSetCommands.Range range = RedisZSetCommands.Range.range()
                .gte(from + "#")   // [ 闭：包含 from 这个月的全部记录
                .lt(to + "#");     // ( 开：不包含 to 这个月
        RedisZSetCommands.Limit lim = RedisZSetCommands.Limit.limit().offset(0).count(cap);

        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .rangeByLex(RedisConstants.COUPON_GRANT_ZSET, range, lim);
            return members == null ? Collections.emptyList() : new ArrayList<>(members);
        } catch (Exception e) {
            // Redis 挂了不能让对账功能整个不可用，退回数据库做同样的范围查询。
            // 注意降级查询的条件必须与上面的 lex 区间严格等价，否则「有没有 Redis」
            // 会得出两个不同的数字，比报错还难查。
            log.error("发放明细 lex 查询失败，降级走数据库 from={}, to={}", from, to, e);
            return fallbackGrantRecords(from, to, cap);
        }
    }

    /** lex 查询的数据库等价实现：grant_period >= from AND < to */
    private List<String> fallbackGrantRecords(String from, String to, int cap) {
        List<UserCoupon> rows = userCouponMapper.selectList(new QueryWrapper<UserCoupon>()
                .select("user_id", "template_id", "grant_period")
                .isNotNull("grant_period")
                .ge("grant_period", from)
                .lt("grant_period", to)
                .orderByAsc("grant_period")
                .last("LIMIT " + cap));
        List<String> out = new ArrayList<>(rows.size());
        for (UserCoupon uc : rows) {
            out.add(uc.getGrantPeriod() + "#" + uc.getUserId() + "#" + uc.getTemplateId());
        }
        return out;
    }

    @Override
    public Map<String, Object> preview(String period) {
        String p = (period == null || period.isEmpty())
                ? buildPeriod(LocalDate.now()) : period;

        List<Member> members = listGrantableMembers();
        int couponTotal = 0;
        Map<Integer, Integer> couponsByLevel = new LinkedHashMap<>();
        for (Member m : members) {
            int n = pickTemplates(m.getLevel()).size();
            couponTotal += n;
            couponsByLevel.merge(m.getLevel(), n, Integer::sum);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("period", p);
        r.put("userTotal", members.size());
        r.put("couponTotal", couponTotal);
        r.put("couponsByLevel", couponsByLevel);
        r.put("note", "只读预演，未写入任何数据");
        return r;
    }

    /** 某等级每月应发张数，取自等级配置；查不到按 0 处理（普通会员就是 0） */
    private int monthlyCouponCnt(int level) {
        for (MemberLevel l : memberService.levels()) {
            if (l.getLevel() != null && l.getLevel() == level) {
                return l.getMonthlyCouponCnt() == null ? 0 : l.getMonthlyCouponCnt();
            }
        }
        return 0;
    }
}
