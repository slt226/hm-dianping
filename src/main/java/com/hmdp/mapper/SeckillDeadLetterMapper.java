package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.SeckillDeadLetter;

/**
 * 秒杀死信记录 Mapper。
 * <p>
 * 只有单表 CRUD 和条件更新，没有复杂 SQL，所以不需要 XML——
 * 对账用的"按 status + create_time 扫描"和"按 voucher_id + status 计数"都能用 Wrapper 表达。
 */
public interface SeckillDeadLetterMapper extends BaseMapper<SeckillDeadLetter> {

}
