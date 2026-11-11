package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.UserCoupon;
import com.hmdp.utils.CouponConst;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户券（券实例）数据访问层（施工手册 Day 3）。
 * <p>
 * 为什么这个接口是空的还能用：
 * 它继承的 BaseMapper 已经提供了 insert / selectById / selectCount / selectPage，
 * Day 3 的领券链路用的正好就是这几个，不必自己写 SQL。
 * 只有「带条件的库存扣减」这种必须靠 SQL 原子性保证的操作，
 * 才需要像 CouponTemplateMapper.deductStock 那样自己写 @Update。
 * <p>
 * 什么时候这里会长出方法：Day 4 做「可用券」需要按 shopId + status + expireTime
 * 联查时，再在这里加自定义查询，届时优先用注解 SQL，保持与现有 Mapper 一致。
 */

public interface UserCouponMapper extends BaseMapper<UserCoupon> {
    @Update("UPDATE tb_user_coupon SET status = " + CouponConst.UC_USED +
            ", use_time = NOW(), order_id = #{orderId} " +
            "WHERE id = #{userCouponId} " +
            "AND user_id = #{userId} " +
            "AND status = " + CouponConst.UC_UNUSED +
            " AND expire_time > NOW()")
    int useOne(@Param("userCouponId") Long userCouponId,
               @Param("userId") Long userId,
               @Param("orderId") Long orderId);

    /**
     * 退券：已使用 → 未使用（Day 5.3）。
     * <p>
     * 和 {@link #useOne} 完全对称，四个条件一个都不能少，只是方向相反：
     * <ul>
     *   <li>{@code id + user_id}：只能退自己的券，防止拿别人的券 id 反复退；</li>
     *   <li>{@code order_id = #{orderId}}：只退这笔订单用掉的券。
     *       少了它，一笔订单的退券接口能把同一张券在另一笔订单上的核销也撤掉；</li>
     *   <li>{@code status = UC_USED}：没被用过的券无从退起，同时也保证重复调用时
     *       第二次必然拿到 0 行，天然幂等；</li>
     *   <li>{@code expire_time > NOW()}：过期券不退。
     *       这条是有意的业务规则——否则用户可以「先用券下单、拖到券快过期再取消」
     *       来变相延长券的寿命。</li>
     * </ul>
     * {@code use_time} 和 {@code order_id} 必须一起清空，否则券虽然变回未使用，
     * 却还挂着上一笔订单的核销痕迹，对账时会被判成脏数据。
     */
    @Update("UPDATE tb_user_coupon SET status = " + CouponConst.UC_UNUSED +
            ", use_time = NULL, order_id = NULL " +
            "WHERE id = #{userCouponId} " +
            "AND user_id = #{userId} " +
            "AND order_id = #{orderId} " +
            "AND status = " + CouponConst.UC_USED +
            " AND expire_time > NOW()")
    int revertOne(@Param("userCouponId") Long userCouponId,
                  @Param("userId") Long userId,
                  @Param("orderId") Long orderId);

    /**
     * 发放会员月度券（Day 9）。
     * <p>
     * 这是整个发放链路里唯一有并发含义的语句，幂等的判据完全落在这条 SQL 上：
     * <pre>
     *   UNIQUE KEY uk_member_period (user_id, template_id, grant_period)
     * </pre>
     * 同一个人、同一个模板、同一个月份，第二次插必然撞索引。
     * <p>
     * <strong>为什么必须是 {@code INSERT IGNORE} 而不是普通 INSERT</strong>：
     * <ul>
     *   <li>定时任务跑在多个实例上时，两个实例会同时给同一个人发同一张券。
     *       普通 INSERT 会让其中一个抛 {@code DuplicateKeyException}，
     *       批量循环就此中断，后面几百个用户一个都发不出去；</li>
     *   <li>手动触发接口被连点三次是常态，每次都不该报错。</li>
     * </ul>
     * {@code INSERT IGNORE} 把「重复」这个<em>预期内</em>的情况变成返回 0，
     * 让调用方能继续处理下一个用户。
     * <p>
     * <strong>代价：它会吞掉所有警告级错误</strong>。比如 {@code expire_time} 传了 NULL
     * （列上是 NOT NULL），MySQL 不会报错，只会静默不插入，你也拿到 0——
     * 和「已发过」的返回值一模一样，分不清。
     * 所以验收必须查库里的实际行数，不能只看返回值。
     * <p>
     * 时间基准统一用数据库的 {@code NOW()}，不传 Java 的 {@code LocalDateTime}：
     * 应用机器和数据库时钟差几秒时，{@code receive_time} 会跑到 {@code expire_time} 之后，
     * 这种脏数据在对账里极难解释。
     *
     * @return 1 表示这次真的发了一张；0 表示本周期已经发过（或插入被忽略）
     */
    @Insert("INSERT IGNORE INTO tb_user_coupon " +
            "(user_id, template_id, coupon_type, shop_id, source, status, " +
            " receive_time, expire_time, grant_period) " +
            "VALUES (#{userId}, #{templateId}, #{couponType}, #{shopId}, #{source}, " +
            CouponConst.UC_UNUSED + ", NOW(), #{expireTime}, #{grantPeriod})")
    int insertIgnoreGrant(UserCoupon uc);
}
