-- ============================================================
-- 秒杀 P0 · 04 死信落库 + 对账 所需 DDL
-- 执行：mysql --defaults-extra-file=$env:TEMP\hmdp-my.cnf hmdp < db\seckill_dead_letter.sql
-- ============================================================

-- 为什么用 CREATE TABLE IF NOT EXISTS 而不是 DROP + CREATE：
--   这张表是"死亡证明"，重跑建表脚本绝不能把已经存在的证据清掉。
CREATE TABLE IF NOT EXISTS `tb_seckill_dead_letter` (
  `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`        bigint       NOT NULL COMMENT '业务订单号（消息里的 orderId）',
  `user_id`         bigint       NOT NULL COMMENT '下单用户id',
  `voucher_id`      bigint       NOT NULL COMMENT '秒杀券id',
  `payload`         varchar(512)          DEFAULT NULL COMMENT '原始消息 JSON，便于人工还原与重放',
  `reason`          varchar(255)          DEFAULT NULL COMMENT '失败原因摘要（x-death 或异常信息）',
  `status`          tinyint      NOT NULL DEFAULT '0' COMMENT '0待补偿 1已补偿 2已忽略(误判死信)',
  `retry_count`     int          NOT NULL DEFAULT '0' COMMENT '人工重放次数（自动对账不增加）',
  `compensate_time` timestamp    NULL     DEFAULT NULL COMMENT '补偿完成时间（不能复用 update_time，它会被任何一次 UPDATE 改写）',
  `create_time`     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '进入死信时间',
  `update_time`     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  -- 为什么唯一索引不建在自增主键之外：order_id 是业务幂等键，
  -- 同一条消息重复进死信（至少一次投递）也只允许留一条记录。
  UNIQUE KEY `uk_order_id` (`order_id`) USING BTREE,
  -- 运维扫描"待补偿清单"：WHERE status=0 ORDER BY create_time，status 必须在前
  KEY `idx_status_create` (`status`,`create_time`) USING BTREE,
  -- 对账门禁：按券统计"还有几条死信没补偿"
  KEY `idx_voucher_status` (`voucher_id`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='秒杀死信记录表';

-- 对账要按 voucher_id 扫出"谁买了这张券"。tb_voucher_order 现有 uk_uid_vid 是
-- (user_id, voucher_id)，前导列是 user_id，用不上 → 不补这个索引，对账会退化成全表扫描。
-- MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS，所以先查 information_schema 再决定是否执行。
SET @exist := (SELECT COUNT(*)
                 FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name   = 'tb_voucher_order'
                  AND index_name   = 'idx_voucher_id');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE tb_voucher_order ADD INDEX idx_voucher_id (voucher_id)',
    'SELECT ''idx_voucher_id already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
