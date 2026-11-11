package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.GrowthLog;
import org.apache.ibatis.annotations.Insert;

/**
 * 成长值流水数据访问层（施工手册 Day 8）。
 * <p>
 * 这个接口看起来是空的，但它是整个幂等方案的落点——
 * 关键不在方法，在表上的那条唯一索引：
 * <pre>
 *   UNIQUE KEY uk_user_reason_biz (user_id, reason, biz_id)
 * </pre>
 * <p>
 * 直接调 {@code insert()} 插入一条重复记录时，MySQL 会返回错误码 1062
 * （Duplicate entry），MyBatis 把它翻译成 Spring 的 DuplicateKeyException。
 * 调用方捕获这个异常就等价于「这笔业务已经加过分了」。
 * <p>
 * 用异常做幂等判断，和用 selectCount 做幂等判断，差别在并发下会暴露：
 * 后者两个线程同时查到 0 行，然后都去加分，成长值翻倍；
 * 前者只有一个 INSERT 能成功，另一个必然撞索引——
 * <strong>判据在数据库，不在应用层</strong>，所以对多实例部署同样成立。
 * <p>
 * 注意：这个异常<strong>会</strong>让事务进入 rollback-only 状态，
 * 所以捕获它的地方有讲究，见 docs/Day8-成长值幂等.md。
 */
public interface GrowthLogMapper extends BaseMapper<GrowthLog> {
    @Insert("INSERT IGNORE INTO tb_growth_log (user_id, change_amount, reason, biz_id) " +
            "VALUES (#{userId}, #{changeAmount}, #{reason}, #{bizId})")
    int insertIgnore(GrowthLog log);
}
