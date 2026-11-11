package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.MemberLevel;

/**
 * 会员等级配置数据访问层（施工手册 Day 8）。
 * <p>
 * 一个方法都不需要：全表只有 4 行，读法永远是 selectList 全量取，
 * 写法则由运营在数据库或后台直接改，接口层不提供写入口。
 * <p>
 * 这也是为什么它不需要任何自定义 SQL——配置表的查询没有条件可言。
 */
public interface MemberLevelMapper extends BaseMapper<MemberLevel> {
}
