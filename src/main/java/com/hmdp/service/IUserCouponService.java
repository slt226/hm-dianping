package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UseCouponRequest;
import com.hmdp.entity.UserCoupon;

/**
 * 用户券业务（施工手册 Day 3 领券链路、Day 5 核销）。
 * <p>
 * 与 ICouponService 的分工：那边管「券的定义」（模板），这边管「用户手上的那张券」。
 * 领券是两部分的分界动作——校验规则写在模板上，结果却要落在用户券表里，
 * 所以 claim 写在这里，但要读 ICouponService 那边的模板数据。
 * <p>
 * 继承 IService 是为了让实现类能 extends ServiceImpl，
 * 白拿 list() / page() / save() 这些通用方法，不用每个都手写一遍。
 */
public interface IUserCouponService extends IService<UserCoupon> {

    /**
     * 领取一张券。
     * <p>
     * 五条规则（面试被问「领券要考虑什么」就照这五条答）：
     * 1. 模板必须上架且在有效期内 —— 查库判断
     * 2. 库存不能为负 —— UPDATE ... WHERE remain_stock &lt;&gt; 0 的影响行数
     * 3. 每人限领 N 张 —— Redis 计数 + 数据库兜底
     * 4. 会员券校验等级 —— 查 tb_member（Day 8 补）
     * 5. 同一张券不能重复入库 —— uk_member_period 唯一索引兜底
     */
    Result claim(Long templateId);
    /**
     * 我的券列表，按状态分页。
     * status 传 null 表示全部；取值见 CouponConst.UC_*
     */
    Result myList(Integer status, int page, int size);
    Result available(Long shopId, Integer orderAmount);

    /**
     * 下单核销：把一组券标记为已使用，并逐张记录核销流水（施工手册 Day 5）。
     * <p>
     * 与 {@link #available} 的关系：那边是「看看能用哪些、最多省多少」的预览，
     * 不碰任何数据；这里才是真正落库。两者共用同一套券规则，
     * 但 available 允许「部分不可用」，useCoupons 只要有一张不可用就整体失败。
     * <p>
     * 事务语义：整个方法是<strong>一个</strong>事务。任何一张券核销失败
     * （被别人抢先用了、刚好过期），已核销的券必须随异常一起回滚，
     * 不能出现「扣了第一张、第二张失败」的半成品状态。
     */
    Result useCoupons(UseCouponRequest req);

    /**
     * 取消订单退券：把这笔订单用掉的券恢复成未使用（施工手册 Day 5.3）。
     * <p>
     * 与 {@link #useCoupons} 的对称关系：
     * <pre>
     *   核销：0 → 1，写 use_time / order_id，插流水
     *   退券：1 → 0，清 use_time / order_id，删流水
     * </pre>
     * 流水必须一起删。留着的话，{@code uk_user_coupon} 唯一索引会让这张券
     * 再也无法核销第二次——退券等于把这券作废了。
     * <p>
     * 过期券不退回（见 {@code UserCouponMapper.revertOne}），
     * 所以返回结果里要带上「哪些退成功了、哪些因为过期没退」，
     * 不能笼统报一句成功。
     */
    Result revertCoupons(Long orderId);

    /**
     * 临期券扫描：找出未来 {@code days} 天内即将过期、且仍未使用的券（施工手册 Day 10）。
     * <p>
     * <strong>为什么这个查询要用 Redis</strong>：
     * 它不是「查我的券」——那是单用户、十几行，走
     * {@code idx_user_status_expire} 索引的 SQL 就够了，套 ZSet 反而是多余的副本。
     * 这是<strong>跨用户</strong>的全站扫描：一张券一张券地问「你什么时候过期」，
     * {@code WHERE expire_time BETWEEN ? AND ? AND status = 0} 在数据量上来后会退化成
     * 大范围回表，而 ZSet 的 score 天然有序，{@code ZRANGEBYSCORE} 直接按区间取，
     * 复杂度只和「取多少条」有关，和总券数无关。
     * <p>
     * 三条契约，实现时必须守住：
     * <ol>
     *   <li><strong>顺序</strong>：结果必须按「快过期的在前」排列。
     *       {@code ZRANGEBYSCORE} 本身是升序返回的，但回表用
     *       {@code selectBatchIds} 之后顺序会变成主键序，必须手动还原；</li>
     *   <li><strong>过滤脏数据</strong>：ZSet 里可能残留已被核销的券 id
     *       （索引删除失败、或 afterCommit 之前进程被杀），回表后要按状态再筛一遍，
     *       否则会给用户提醒一张早就用掉的券；</li>
     *   <li><strong>降级</strong>：Redis 不可用时不能返回错误，要退回数据库查询。</li>
     * </ol>
     *
     * @param days  向前看的天数，超过 0；建议上限 7，太长就没有「临期」的意义了
     * @param limit 最多返回多少条，防止运营界面一次拉几万条
     */
    Result listExpiringSoon(int days, int limit);

}
