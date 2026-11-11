package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.GrowthLogVO;
import com.hmdp.dto.MemberVO;
import com.hmdp.dto.Result;
import com.hmdp.entity.GrowthLog;
import com.hmdp.entity.Member;
import com.hmdp.entity.MemberLevel;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.GrowthLogMapper;
import com.hmdp.mapper.MemberLevelMapper;
import com.hmdp.mapper.MemberMapper;
import com.hmdp.service.IMemberService;
import com.hmdp.utils.MemberConst;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 会员体系实现（施工手册 Day 8）。
 * <p>
 * 本类里读的方法都写完了，留给你写的是 {@link #addGrowth}——
 * 那是整个会员模块唯一有并发风险的地方，也是面试唯一会被追问的地方。
 * 实现思路见 docs/Day8-成长值幂等.md，动手前先把那份文档看完再写。
 */
@Slf4j
@Service
public class MemberServiceImpl implements IMemberService {

    @Resource
    private MemberMapper memberMapper;

    @Resource
    private MemberLevelMapper memberLevelMapper;

    @Resource
    private GrowthLogMapper growthLogMapper;

    /**
     * 等级配置的内存缓存。
     * <p>
     * 为什么敢缓存：tb_member_level 是配置表，只有 4 行，运营改动频率以「月」计，
     * 而等级判定是每次领券、每次展示会员页都要走的读路径。
     * 每次都查库相当于为了 4 行几乎不变的数据，给数据库加一份无谓的 QPS。
     * <p>
     * 代价是运营改了配置要重启才生效。真要做到「改完立刻生效」，
     * 就得在写入路径上发一个失效消息——那是后台配置功能，本项目不需要。
     */
    private volatile List<MemberLevel> levelCache = new ArrayList<>();

    @PostConstruct
    public void loadLevels() {
        try {
            List<MemberLevel> list = memberLevelMapper.selectList(
                    new QueryWrapper<MemberLevel>().orderByAsc("level"));
            if (list != null && !list.isEmpty()) {
                levelCache = list;
                log.info("会员等级配置加载完成，共 {} 档：{}", list.size(), list);
            } else {
                log.warn("tb_member_level 为空，会员等级判定将全部按 0 级处理");
            }
        } catch (Exception e) {
            // 配置加载失败不能让应用起不来：会员等级是增值功能，
            // 挂了顶多是所有人按普通会员处理，不该连累下单和领券。
            log.error("会员等级配置加载失败，降级为全部 0 级", e);
        }
    }

    @Override
    public MemberVO myInfo() {
        Long userId = currentUserId();

        Member m = memberMapper.selectOne(
                new QueryWrapper<Member>().eq("user_id", userId));

        // 没有会员行等价于 0 级 0 成长值，不是异常
        int growth = (m == null || m.getGrowth() == null) ? 0 : m.getGrowth();
        int level = (m == null || m.getLevel() == null) ? MemberConst.LEVEL_NORMAL : m.getLevel();

        MemberLevel cur = findLevel(level);
        // 配置里找不到当前等级（比如运营删过档位），兜底到普通会员，
        // 否则下面的门槛相减会 NPE，会员页直接白屏。
        if (cur == null) {
            cur = findLevel(MemberConst.LEVEL_NORMAL);
        }
        MemberLevel next = findLevel(level + 1);

        MemberVO vo = new MemberVO();
        vo.setUserId(userId);
        vo.setLevel(level);
        vo.setGrowth(growth);
        vo.setLevelName(cur == null ? "会员" : cur.getName());
        vo.setCurrentThreshold(cur == null ? 0 : cur.getGrowthThreshold());
        vo.setDiscountRate(cur == null ? 100 : cur.getDiscountRate());
        vo.setIcon(cur == null ? null : cur.getIcon());
        vo.setBenefitDesc(cur == null ? null : cur.getBenefitDesc());

        if (next == null) {
            // 已经是最高的档位
            vo.setProgress(100);
            vo.setGrowthToNext(0);
        } else {
            vo.setNextLevel(next.getLevel());
            vo.setNextLevelName(next.getName());
            vo.setNextThreshold(next.getGrowthThreshold());
            vo.setGrowthToNext(Math.max(0, next.getGrowthThreshold() - growth));
            vo.setProgress(calcProgress(growth, cur, next));
        }
        return vo;
    }

    /**
     * 计算「当前等级内」的进度，而不是「距满级」的进度。
     * <p>
     * 举个例子就能看出差别：金卡门槛 2000、黑卡 8000，用户成长值 2100。
     * 按总量算 2100/8000 = 26%，用户会觉得「我涨了这么久怎么才四分之一」；
     * 按区间算 (2100-2000)/(8000-2000) = 1.7%，才是真实的「离下一级还有多远」。
     * <p>
     * 全程整数运算，不用 double：进度条差 1% 无所谓，但金额类计算差 1 分钱要命，
     * 保持「这个项目里凡是算数都用整数」的一致性。
     */
    private int calcProgress(int growth, MemberLevel cur, MemberLevel next) {
        int span = next.getGrowthThreshold() - cur.getGrowthThreshold();
        if (span <= 0) {
            return 100;
        }
        int done = growth - cur.getGrowthThreshold();
        if (done <= 0) {
            return 0;
        }
        return Math.min(100, done * 100 / span);
    }

    @Override
    public List<MemberLevel> levels() {
        // 返回缓存的副本，不让调用方拿到内部集合引用后随手 add/clear
        return new ArrayList<>(levelCache);
    }

    @Override
    public Result growthLogs(int page, int size) {
        Long userId = currentUserId();
        // 上限 50：不限制的话，size=100000 一次就能把几十万行流水拉进内存
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);

        Page<GrowthLog> p = growthLogMapper.selectPage(
                new Page<>(safePage, safeSize),
                new QueryWrapper<GrowthLog>()
                        .eq("user_id", userId)
                        // create_time 精度只到秒，同一秒内的多条流水顺序不稳定，
                        // 补一个 id 兜底，保证翻页时不会重复或漏行
                        .orderByDesc("create_time")
                        .orderByDesc("id"));

        List<GrowthLogVO> list = new ArrayList<>(p.getRecords().size());
        for (GrowthLog g : p.getRecords()) {
            GrowthLogVO vo = new GrowthLogVO();
            vo.setId(g.getId());
            vo.setChangeAmount(g.getChangeAmount());
            vo.setReason(g.getReason());
            vo.setReasonText(reasonText(g.getReason()));
            vo.setBizId(g.getBizId());
            vo.setCreateTime(g.getCreateTime());
            list.add(vo);
        }
        return Result.ok(list, p.getTotal());
    }

    @Override
    public int levelOf(Long userId) {
        if (userId == null) {
            return MemberConst.LEVEL_NORMAL;
        }
        Member m = memberMapper.selectOne(
                new QueryWrapper<Member>().eq("user_id", userId));
        if (m == null || m.getLevel() == null) {
            return MemberConst.LEVEL_NORMAL;
        }
        return m.getLevel();
    }

    @Override
    public String levelName(int level) {
        MemberLevel l = findLevel(level);
        return l == null ? "会员" : l.getName();
    }

    /**
     * ⚠️ 这个方法留给你实现。
     * <p>
     * 接口注释里列了五条验收契约（幂等、并发、不丢更新、原子性、自动升级）。
     * 别急着写 SQL，先把 docs/Day8-成长值幂等.md 里「为什么先查后改一定会错」
     * 那一段看完——它会解释清楚为什么下面这些现成的零件要按某个顺序拼。
     * <p>
     * 已经给你备好的零件（都在 Mapper 层，直接调）：
     * <ul>
     *   <li>{@code memberMapper.initIfAbsent(userId)}：懒创建会员行，重复调用安全</li>
     *   <li>{@code memberMapper.addGrowth(userId, delta)}：原子自增，不会丢更新</li>
     *   <li>{@code memberMapper.upgradeLevel(userId, level)}：只升不降</li>
     *   <li>{@code growthLogMapper.insert(log)}：写流水，重复会抛 DuplicateKeyException</li>
     *   <li>{@code levelCache}：等级列表，用来判断加完之后该升到几级</li>
     * </ul>
     * <p>
     * 需要你自己想清楚的三件事：
     * <ol>
     *   <li>「先写流水」还是「先加成长值」，为什么这个顺序不能反；</li>
     *   <li>捕获 DuplicateKeyException 之后，这个方法还能继续做别的事吗；</li>
     *   <li>{@code @Transactional} 该加在哪一层，加了之后第 2 点会变成什么问题、怎么绕开。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addGrowth(Long userId, int amount, int reason, Long bizId) {

        if (userId == null || bizId == null) throw new BizException("参数不能为空");
        if (amount == 0) throw new BizException("成长值变动不能为 0");
        memberMapper.initIfAbsent(userId);        // ← 必须最前面

        GrowthLog log = new GrowthLog();
        log.setUserId(userId);
        log.setChangeAmount(amount);
        log.setReason(reason);
        log.setBizId(bizId);
        int inserted=growthLogMapper.insertIgnore(log);
        if (inserted == 0) {
            return false;
        }
        memberMapper.addGrowth(userId, amount);
        Member fresh = memberMapper.selectOne(new QueryWrapper<Member>().eq("user_id", userId));
        if(fresh == null) {
            throw new BizException("会员信息异常");
        }
        int newGrowth = fresh.getGrowth();
        int target=MemberConst.LEVEL_NORMAL;
        for(MemberLevel l:levelCache){
            if(newGrowth >=l.getGrowthThreshold()){
                target=l.getLevel();
            }
        }
        if(target>fresh.getLevel()){
            memberMapper.upgradeLevel(userId, target);
        }
        return true;
    }

    private MemberLevel findLevel(int level) {
        for (MemberLevel l : levelCache) {
            if (l.getLevel() != null && l.getLevel() == level) {
                return l;
            }
        }
        return null;
    }

    private String reasonText(Integer reason) {
        if (reason == null) {
            return "其他";
        }
        switch (reason) {
            case MemberConst.REASON_ORDER:
                return "消费";
            case MemberConst.REASON_SIGN:
                return "签到";
            case MemberConst.REASON_COMMENT:
                return "评价";
            case MemberConst.REASON_INVITE:
                return "邀请";
            case MemberConst.REASON_EXPIRE:
                return "过期扣除";
            case MemberConst.REASON_REFUND:
                return "退款扣回";
            default:
                return "其他";
        }
    }

    private Long currentUserId() {
        return UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
    }
}
