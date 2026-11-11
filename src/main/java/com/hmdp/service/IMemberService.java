package com.hmdp.service;

import com.hmdp.dto.MemberVO;
import com.hmdp.dto.Result;
import com.hmdp.entity.MemberLevel;

import java.util.List;

/**
 * 会员体系服务（施工手册 Day 8）。
 * <p>
 * 分成两类方法，看名字就能区分：
 * <ul>
 *   <li><strong>读</strong>：myInfo / levels / growthLogs，把数据整理成界面要的形状；</li>
 *   <li><strong>写</strong>：addGrowth，唯一会改变成长值的入口。</li>
 * </ul>
 * 读写分开的直接好处是「只有一处能加分」，将来要查「谁给用户加了分」，
 * 在这些调用点上打日志就够了，不用满项目搜 update。
 */
public interface IMemberService {

    /**
     * 当前登录用户的会员信息。
     * <p>
     * 用户没有会员行时<strong>不报错</strong>，返回 level=0、growth=0 的默认视图。
     * 这条约定很重要：新注册用户第一次点进会员中心，看到的是「普通会员 0 成长值」，
     * 而不是一个 500。
     */
    MemberVO myInfo();

    /**
     * 全部等级配置，按 level 升序。
     * <p>
     * 给「会员权益说明页」用，未登录也能看——它是配置不是个人数据。
     */
    List<MemberLevel> levels();

    /**
     * 我的成长值流水，按时间倒序分页。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     */
    Result growthLogs(int page, int size);

    /**
     * 查询某用户当前等级，查不到按 0（普通会员）返回。
     * <p>
     * 单独抽出来是给「领券校验会员门槛」用的：那里只需要一个 int，
     * 没必要为了判断门槛把整个 MemberVO 甚至等级配置都查一遍。
     * <p>
     * 注意这是<strong>纯读</strong>方法，不会顺手帮用户创建会员行——
     * 领券失败的用户不该在库里留下一条会员记录。
     */
    int levelOf(Long userId);

    /**
     * 等级序号转中文名，用于拼「仅限金卡会员领取」这类提示。
     * <p>
     * 单独开一个方法而不是让调用方自己遍历 levels()：调用方要的只是一个字符串，
     * 每次都遍历一遍配置列表既啰嗦又容易忘了处理「等级不存在」的情况。
     *
     * @param level 等级序号，查不到时返回「会员」这种兜底文案，不返回 null
     */
    String levelName(int level);

    /**
     * 增加成长值（施工手册 Day 8 的核心实现点）。
     * <p>
     * 业务语义：用户因为某种原因（消费/签到/评价/邀请）获得成长值，
     * 累加到 tb_member.growth，写一条 tb_growth_log 明细，并在跨过门槛时升级。
     * <p>
     * <strong>必须满足的契约</strong>（验收时会逐条验）：
     * <ol>
     *   <li><strong>幂等</strong>：同一个 (userId, reason, bizId) 重复调用任意多次，
     *       成长值只能增加一次，流水只能有一行。</li>
     *   <li><strong>并发安全</strong>：10 个线程同时用同一个 bizId 调用，
     *       结果与调用 1 次完全相同——不能出现「查的时候都说不存在，
     *       然后各自加了一次」。</li>
     *   <li><strong>不丢更新</strong>：10 个线程用 10 个<em>不同</em> bizId 各加 10 分，
     *       最终 growth 必须是 +100，不能是 +10。</li>
     *   <li><strong>原子性</strong>：流水写成功但成长值没加上（或反之）都不允许，
     *       中途失败必须整体回滚。</li>
     *   <li><strong>自动升级</strong>：加完之后如果跨过了某档门槛，level 要跟着变，
     *       且只能升不能降。</li>
     * </ol>
     *
     * @param userId 用户 id
     * @param amount 变动值，正数增加；负数表示扣减（退款扣回用）
     * @param reason 变动原因，见 {@link com.hmdp.utils.MemberConst} 的 REASON_*
     * @param bizId  业务单号，幂等的依据。消费场景传订单 id
     * @return true 表示本次真的加了；false 表示这条业务已经处理过（重复请求）
     */
    boolean addGrowth(Long userId, int amount, int reason, Long bizId);
}
