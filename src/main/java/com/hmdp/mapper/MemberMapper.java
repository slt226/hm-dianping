package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Member;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户会员信息数据访问层（施工手册 Day 8）。
 * <p>
 * 这里三个方法都不是普通的 CRUD，而是把「并发下必须原子完成」的动作下沉到了 SQL 里。
 * 理由和 Day 3 扣库存完全一样：能交给数据库一条语句做完的，就不要在 Java 里
 * 先查后改——那中间隔着一个时间窗，多线程一进来就出错。
 */
public interface MemberMapper extends BaseMapper<Member> {

    /**
     * 懒创建会员行。已存在时静默忽略，不会抛异常。
     * <p>
     * 用 INSERT IGNORE 而不是「先 selectCount 再 insert」：后者在并发下
     * 两个请求都会看到「不存在」，然后同时 insert，第二个撞 uk_user 直接报错。
     * <p>
     * 注意 INSERT IGNORE 会把<strong>所有</strong>错误都降级成警告，
     * 包括字段超长、类型不匹配。这里只有三个字段且全部由服务端填，
     * 风险可控；换个场景（比如带用户输入）就不能这么用。
     */
    @Insert("INSERT IGNORE INTO tb_member (user_id, level, growth) " +
            "VALUES (#{userId}, 0, 0)")
    int initIfAbsent(@Param("userId") Long userId);

    /**
     * 成长值原子自增。
     * <p>
     * {@code growth = growth + #{delta}} 是<strong>一条语句</strong>：
     * 读和写在同一把行锁内完成，五个并发请求打进来也是串行累加。
     * 写成「先 select 拿到 100，再 set 成 120」就会丢更新——
     * 两个线程都读到 100，都写 120，实际应该是 140。
     * <p>
     * delta 允许为负，用于退款扣回。扣成负数这件事<strong>不在这里拦</strong>，
     * 由调用方决定要不要允许，SQL 只管加减。
     */
    @Update("UPDATE tb_member SET growth = growth + #{delta} WHERE user_id = #{userId}")
    int addGrowth(@Param("userId") Long userId, @Param("delta") Integer delta);

    /**
     * 升级。带 {@code level < #{level}} 条件，保证只升不降。
     * <p>
     * 这个条件不是多余的防御：成长值扣回后用户实际已经达不到当前等级，
     * 但业务上通常不降级（降级会让用户立刻失去权益，体验极差）。
     * 把「不降级」写成 SQL 条件而不是 Java 里的 if，好处是并发下也不会被
     * 两个线程交叉执行成「先降后升」。
     * <p>
     * 返回值是受影响行数：1 表示真的升级了，0 表示不需要升（等级已经够高）。
     */
    @Update("UPDATE tb_member SET level = #{level} " +
            "WHERE user_id = #{userId} AND level < #{level}")
    int upgradeLevel(@Param("userId") Long userId, @Param("level") Integer level);
}
