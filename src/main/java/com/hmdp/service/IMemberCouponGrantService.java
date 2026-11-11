package com.hmdp.service;

import com.hmdp.entity.CouponTemplate;
import com.hmdp.entity.Member;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 会员月度券发放服务（施工手册 Day 9）。
 * <p>
 * 这个服务只做一件事：<strong>给一个会员，发他所在等级应有的月度券</strong>。
 * 「批量扫谁」不在这里，「什么时候跑」也不在这里——那是定时任务的事。
 * <p>
 * 分层这么切的理由不是洁癖，是事务边界：
 * <ol>
 *   <li>批量方法如果是「一个事务」，第 200 个用户失败会把前 199 个一起回滚，
 *       而且事务长到几百条 INSERT，锁持有时间和回滚段都会失控；</li>
 *   <li>单用户方法各自一个事务，失败的人记下来重跑即可，成功的不受影响。</li>
 * </ol>
 * 所以 {@link #grantToUser} 才是事务边界所在，它必须能被<strong>跨 Bean 调用</strong>
 * （从定时任务里调），而不能是「类内部自己调自己」——后者会绕过 Spring 代理，注解直接失效。
 */
public interface IMemberCouponGrantService {

    /**
     * 构造发放周期键，格式固定为 {@code yyyy-MM}，例如 {@code 2026-11}。
     * <p>
     * 这个值是 {@code tb_user_coupon.grant_period}，参与唯一索引
     * {@code uk_member_period (user_id, template_id, grant_period)}。
     * <p>
     * <strong>它必须由服务端算，不能接受客户端传参。</strong>
     * 一旦可以由调用方指定，就等于把「这一笔算不算重复发放」的判断权交给了外部：
     * 换个 period 传进来，同一个人同一个月就能重复领无数次。
     *
     * @param date 基准日期，通常传 {@code LocalDate.now()}
     * @return 形如 {@code 2026-11} 的 7 位字符串
     */
    String buildPeriod(LocalDate date);

    /**
     * 给单个会员发放某周期的会员券（Day 9 的核心实现点）。
     * <p>
     * <strong>契约</strong>：
     * <ol>
     *   <li><strong>幂等</strong>：同一 (userId, templateId, period) 重复调用任意多次，
     *       库里只能有 1 行，第二次起返回 0；</li>
     *   <li><strong>不报错</strong>：「本周期已发过」是预期内的正常情况，
     *       不能抛异常——批量循环里一个异常就会打断整批；</li>
     *   <li><strong>只发一张</strong>：一个模板一个周期只能有一张，
     *       这是唯一索引决定的，不是业务上随便定的。</li>
     * </ol>
     *
     * @param userId 用户 id
     * @param level  用户当前等级，由调用方查好传入，避免这里再查一次库
     * @param period 发放周期，由 {@link #buildPeriod} 生成
     * @return 本次实际发出的券张数；0 表示本周期已经发过（或该等级没有可发模板）
     */
    int grantToUser(Long userId, int level, String period);

    /**
     * 本轮可发放的会员列表（{@code level > 0}）。
     * <p>
     * 只取有等级的会员：普通会员的 {@code monthly_coupon_cnt} 是 0，
     * 扫进来也是白扫。同时只 select 两列，批量场景下没必要把整行拉出来。
     */
    List<Member> listGrantableMembers();

    /**
     * 只读预演：这个周期会给多少人、发多少张。
     * <p>
     * 定时任务上线前先跑一次这个，比直接放任务跑、事后查库要安全得多。
     * 它不写任何数据。
     */
    Map<String, Object> preview(String period);

    /**
     * 按周期区间拉发放明细（Day 10，{@code ZRANGEBYLEX}）。
     * <p>
     * 用途是对账和运营导出：「2026 年一季度一共发过哪些券、发给了谁」。
     * 这类查询的特点是<strong>按周期做范围扫描</strong>，而不是等值匹配——
     * 等值（某一个人、某一个周期）有唯一索引就够了，范围才是 ZSet 真正值钱的地方。
     * <p>
     * 为什么能按字典序扫：索引里的 member 是 {@code {period}#{userId}#{templateId}}，
     * period 在前且定长 7 位，所以字符串比较的结果和日期比较一致。
     * <p>
     * <strong>前提</strong>：这个 ZSet 里所有成员的 score 必须相同（都是 0）。
     * {@code ZRANGEBYLEX} 只在 score 相等时才按 member 字典序排序，
     * 一旦混入不同 score，结果就变成「先按 score 分组、组内才按字典序」，跨组取出来是乱的。
     *
     * @param fromPeriod 起始周期（含），格式 yyyy-MM；传 null 默认近 6 个月
     * @param toPeriod   结束周期（<strong>不含</strong>），格式 yyyy-MM；传 null 默认下个月
     * @param limit      最多返回多少条
     * @return 形如 {@code 2026-11#1016#5} 的明细行
     */
    List<String> listGrantRecords(String fromPeriod, String toPeriod, int limit);

    /**
     * 某等级本轮应发的模板列表。
     * <p>
     * 已由脚手架实现，直接调用：按 {@code type=3 会员红包 + 上架 + member_level <= 当前等级}
     * 查模板，再用该等级的 {@code monthly_coupon_cnt} 截断，防止运营配了一堆模板导致超发。
     */
    List<CouponTemplate> pickTemplates(int level);
}
