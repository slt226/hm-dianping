-- =====================================================================
-- 本地生活服务平台 · 改造升级脚本 V2
-- 内容：红包卡券体系 / 会员红包 / 搜索推荐 / AI 客服
-- 环境：MySQL 8.0.45（utf8mb4），与 hmdp.sql 同库
-- 说明：本脚本只新增表，不修改、不删除任何原有表，可安全重复执行前先备份
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- 1. 券模板（红包 / 商家券 / 会员红包 统一模板）
--    金额单位统一为「分」，与 tb_voucher.pay_value 保持一致，避免浮点误差
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_coupon_template`;
CREATE TABLE `tb_coupon_template` (
  `id`               bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`             varchar(64)  NOT NULL                COMMENT '券名称，如「满100减30」',
  `type`             tinyint(4)   NOT NULL                COMMENT '1平台红包 2商家券 3会员红包',
  `acquire_mode`     tinyint(4)   NOT NULL DEFAULT 2      COMMENT '获取方式 1付费购买(秒杀) 2主动领取 3系统发放',
  `shop_id`          bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=全平台通用',
  `discount_type`    tinyint(4)   NOT NULL                COMMENT '1满减 2无门槛直减 3折扣',
  `threshold_amount` int(11)      NOT NULL DEFAULT 0      COMMENT '使用门槛（分），0=无门槛',
  `discount_amount`  int(11)      NOT NULL DEFAULT 0      COMMENT '优惠金额（分），折扣券填 0',
  `discount_rate`    int(11)      NOT NULL DEFAULT 100    COMMENT '折扣率，88 表示 8.8 折，100=不打折',
  `max_discount`     int(11)      NOT NULL DEFAULT 0      COMMENT '折扣券封顶金额（分），0=不封顶',
  `pay_value`        int(11)      NOT NULL DEFAULT 0      COMMENT '购买价（分），仅 acquire_mode=1 有效',
  `total_stock`      int(11)      NOT NULL DEFAULT 0      COMMENT '总库存，-1=不限量',
  `remain_stock`     int(11)      NOT NULL DEFAULT 0      COMMENT '剩余库存',
  `per_user_limit`   int(11)      NOT NULL DEFAULT 1      COMMENT '每人限领张数',
  `valid_type`       tinyint(4)   NOT NULL DEFAULT 1      COMMENT '1固定区间 2领取后N天',
  `valid_start`      datetime     NULL                    COMMENT 'valid_type=1 时生效时间',
  `valid_end`        datetime     NULL                    COMMENT 'valid_type=1 时失效时间',
  `valid_days`       int(11)      NOT NULL DEFAULT 0      COMMENT 'valid_type=2 时有效天数',
  `member_level`     tinyint(4)   NOT NULL DEFAULT 0      COMMENT '最低会员等级，0=不限',
  `stackable`        tinyint(1)   NOT NULL DEFAULT 0      COMMENT '1=可与其他券叠加 0=互斥（同单只能用一张）',
  `legacy_voucher_id` bigint(20) UNSIGNED NULL            COMMENT '关联原 tb_voucher.id，迁移期使用',
  `status`           tinyint(4)   NOT NULL DEFAULT 1      COMMENT '1上架 0下架',
  `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop_status` (`shop_id`, `status`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_legacy` (`legacy_voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='券模板（红包/商家券/会员红包统一）';

-- ---------------------------------------------------------------------
-- 2. 用户券实例（用户持有的每一张券）
--    关键：uk_member_period 利用 MySQL 唯一索引对 NULL 的宽容性
--          非会员发放的券 grant_period 为 NULL，互不冲突；
--          会员月度券 grant_period = '2026-10'，天然防重复发放
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_user_coupon`;
CREATE TABLE `tb_user_coupon` (
  `id`           bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`      bigint(20) UNSIGNED NOT NULL,
  `template_id`  bigint(20) UNSIGNED NOT NULL,
  `coupon_type`  tinyint(4)   NOT NULL              COMMENT '冗余自模板，避免列表页每次 join',
  `shop_id`      bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '冗余自模板，0=全平台',
  `source`       tinyint(4)   NOT NULL              COMMENT '1付费购买 2主动领取 3系统发放 4会员月度 5活动赠送',
  `status`       tinyint(4)   NOT NULL DEFAULT 0    COMMENT '0未使用 1已使用 2已过期 3锁定中',
  `receive_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expire_time`  datetime     NOT NULL              COMMENT '绝对过期时间，领取时即算好，避免每次判断 valid_type',
  `use_time`     datetime     NULL,
  `order_id`     bigint(20) UNSIGNED NULL           COMMENT '核销的订单号',
  `grant_period` char(7)      NULL                  COMMENT '发放周期 2026-10，仅会员月度券有值',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_period` (`user_id`, `template_id`, `grant_period`),
  KEY `idx_user_status_expire` (`user_id`, `status`, `expire_time`),
  KEY `idx_order` (`order_id`),
  KEY `idx_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户券实例';

-- ---------------------------------------------------------------------
-- 3. 券核销流水（一笔订单用了几张券，各抵多少）
--    uk_user_coupon 是「一张券只能用一次」的第二道防线
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_coupon_use_log`;
CREATE TABLE `tb_coupon_use_log` (
  `id`              bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_coupon_id`  bigint(20) UNSIGNED NOT NULL,
  `user_id`         bigint(20) UNSIGNED NOT NULL,
  `order_id`        bigint(20) UNSIGNED NOT NULL,
  `shop_id`         bigint(20) UNSIGNED NOT NULL,
  `order_amount`    int(11) NOT NULL COMMENT '订单原价（分）',
  `discount_amount` int(11) NOT NULL COMMENT '本券实际抵扣（分）',
  `pay_amount`      int(11) NOT NULL COMMENT '本单实付（分），仅第一张券写入，其余为 0',
  `create_time`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon` (`user_coupon_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='券核销流水';

-- ---------------------------------------------------------------------
-- 4. 会员等级配置
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_member_level`;
CREATE TABLE `tb_member_level` (
  `level`              tinyint(4)  NOT NULL,
  `name`               varchar(32) NOT NULL             COMMENT '普通/银卡/金卡/黑卡',
  `growth_threshold`   int(11)     NOT NULL             COMMENT '成长值门槛',
  `monthly_redpack`    int(11)     NOT NULL DEFAULT 0   COMMENT '每月红包总额（分）',
  `monthly_coupon_cnt` int(11)     NOT NULL DEFAULT 0   COMMENT '每月发放红包张数',
  `discount_rate`      int(11)     NOT NULL DEFAULT 100 COMMENT '会员全场折扣，100=不打折',
  `icon`               varchar(255) NOT NULL DEFAULT '',
  `benefit_desc`       varchar(512) NOT NULL DEFAULT '',
  `create_time`        datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会员等级配置';

-- ---------------------------------------------------------------------
-- 5. 用户会员信息
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_member`;
CREATE TABLE `tb_member` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     bigint(20) UNSIGNED NOT NULL,
  `level`       tinyint(4)  NOT NULL DEFAULT 0,
  `growth`      int(11)     NOT NULL DEFAULT 0    COMMENT '累计成长值',
  `expire_time` datetime    NULL                  COMMENT '付费会员到期时间，NULL=永久（成长值会员）',
  `auto_renew`  tinyint(1)  NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`),
  KEY `idx_level` (`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户会员信息';

-- ---------------------------------------------------------------------
-- 6. 成长值流水（uk_user_reason_biz 防同一订单重复加分）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_growth_log`;
CREATE TABLE `tb_growth_log` (
  `id`            bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       bigint(20) UNSIGNED NOT NULL,
  `change_amount` int(11)    NOT NULL             COMMENT '正数增加，负数扣减',
  `reason`        tinyint(4) NOT NULL             COMMENT '1消费 2签到 3评价 4邀请 5过期扣除 6退款扣回',
  `biz_id`        bigint(20) UNSIGNED NULL        COMMENT '业务单号，用于幂等',
  `create_time`   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_reason_biz` (`user_id`, `reason`, `biz_id`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='成长值流水';

-- ---------------------------------------------------------------------
-- 7. 搜索日志（热词榜 + 个性化推荐的数据源）
--    注意：本表增长快，建议只保留 90 天，用定时任务清理
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_search_log`;
CREATE TABLE `tb_search_log` (
  `id`            bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       bigint(20) UNSIGNED NULL,
  `keyword`       varchar(64) NOT NULL,
  `result_cnt`    int(11)     NOT NULL DEFAULT 0  COMMENT '搜索结果数，用于发现「无结果词」',
  `click_shop_id` bigint(20) UNSIGNED NULL        COMMENT '点击了哪个结果',
  `create_time`   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_keyword_time` (`keyword`, `create_time`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='搜索日志';

-- ---------------------------------------------------------------------
-- 8. 用户行为（推荐打分用，可只保留 30 天）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_user_behavior`;
CREATE TABLE `tb_user_behavior` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`     bigint(20) UNSIGNED NOT NULL,
  `shop_id`     bigint(20) UNSIGNED NOT NULL,
  `behavior`    tinyint(4) NOT NULL            COMMENT '1曝光 2点击 3领券 4下单 5收藏',
  `weight`      int(11)    NOT NULL DEFAULT 1  COMMENT '行为权重，下单 > 领券 > 点击 > 曝光',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_shop_time` (`shop_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户行为';

-- ---------------------------------------------------------------------
-- 9. AI 会话（summary 用于长对话压缩，控制 token 成本）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_ai_session`;
CREATE TABLE `tb_ai_session` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`  char(32)   NOT NULL,
  `user_id`     bigint(20) UNSIGNED NOT NULL,
  `scene`       tinyint(4) NOT NULL DEFAULT 1 COMMENT '1客服 2导购（搜索推荐场景）',
  `status`      tinyint(4) NOT NULL DEFAULT 0 COMMENT '0进行中 1已结束 2已转人工',
  `summary`     varchar(512) NOT NULL DEFAULT '' COMMENT '超过 10 轮后的历史摘要',
  `turn_count`  int(11)    NOT NULL DEFAULT 0,
  `token_used`  int(11)    NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`session_id`),
  KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 会话';

-- ---------------------------------------------------------------------
-- 10. AI 消息（只存有效轮次，tool 消息也落库便于排查）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_ai_message`;
CREATE TABLE `tb_ai_message` (
  `id`         bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` char(32) NOT NULL,
  `role`       tinyint(4) NOT NULL          COMMENT '1用户 2assistant 3tool 4人工客服',
  `content`    text     NOT NULL,
  `tool_name`  varchar(64)  NULL            COMMENT 'function calling 的函数名',
  `tool_args`  varchar(1024) NULL           COMMENT '入参 JSON（脱敏后）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI 消息';

-- ---------------------------------------------------------------------
-- 11. 知识库文档
--     MVP 阶段：category 路由 + 关键词匹配，不需要向量
--     文档 > 200 条或出现明显语义召回问题时，再上向量检索
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_knowledge_doc`;
CREATE TABLE `tb_knowledge_doc` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `category`    varchar(32)  NOT NULL           COMMENT '退款规则/券使用/会员权益/营业信息/发票',
  `title`       varchar(128) NOT NULL,
  `question`    varchar(255) NOT NULL DEFAULT '' COMMENT '标准问法，用于关键词召回',
  `content`     text         NOT NULL           COMMENT '答案正文，也是塞进 prompt 的片段',
  `keywords`    varchar(255) NOT NULL DEFAULT '' COMMENT '关键词，逗号分隔',
  `priority`    int(11)      NOT NULL DEFAULT 0  COMMENT '命中多条时按优先级取',
  `status`      tinyint(4)   NOT NULL DEFAULT 1,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`, `status`),
  KEY `idx_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客服知识库';

-- ---------------------------------------------------------------------
-- 12. 人工客服工单（AI 兜底转人工）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `tb_service_ticket`;
CREATE TABLE `tb_service_ticket` (
  `id`          bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `ticket_no`   char(16)   NOT NULL,
  `user_id`     bigint(20) UNSIGNED NOT NULL,
  `session_id`  char(32)   NULL              COMMENT '从哪次 AI 会话转来',
  `category`    varchar(32) NOT NULL         COMMENT '券问题/退款/订单/投诉/其他',
  `content`     varchar(1024) NOT NULL       COMMENT '用户原话 + AI 生成的摘要',
  `status`      tinyint(4) NOT NULL DEFAULT 0 COMMENT '0待接 1处理中 2已解决 3已关闭',
  `assignee`    varchar(32) NOT NULL DEFAULT '',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  KEY `idx_status_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人工客服工单';

-- =====================================================================
-- 初始化数据
-- =====================================================================

-- 会员等级：4 档，成长值免费升级（本地生活场景比付费会员门槛低，转化更好）
INSERT INTO `tb_member_level`
  (`level`, `name`, `growth_threshold`, `monthly_redpack`, `monthly_coupon_cnt`, `discount_rate`, `icon`, `benefit_desc`) VALUES
  (0, '普通会员', 0,     0,     0, 100, '', '注册即享，无门槛'),
  (1, '银卡会员', 500,   1000,  2, 98,  '', '每月 2 张红包，全场 9.8 折'),
  (2, '金卡会员', 2000,  3000,  3, 95,  '', '每月 3 张红包，全场 9.5 折，专属客服'),
  (3, '黑卡会员', 8000,  8000,  4, 90,  '', '每月 4 张红包，全场 9 折，优先转人工');

-- 券模板示例
INSERT INTO `tb_coupon_template`
  (`name`, `type`, `acquire_mode`, `shop_id`, `discount_type`, `threshold_amount`, `discount_amount`,
   `discount_rate`, `max_discount`, `pay_value`, `total_stock`, `remain_stock`, `per_user_limit`,
   `valid_type`, `valid_start`, `valid_end`, `valid_days`, `member_level`, `stackable`, `status`) VALUES
  -- 平台新人红包（无门槛直减，可叠加）
  ('新人专享 5 元红包', 1, 3, 0,  2,   0,  500, 100,    0,    0, 10000, 10000, 1, 2, NULL, NULL, 7,  0, 1, 1),
  -- 平台大促红包（满 100 减 15，可叠加）
  ('全场满 100 减 15',   1, 2, 0,  1, 10000, 1500, 100,   0,    0,  5000,  5000, 2, 1, '2026-10-01 00:00:00', '2026-12-31 23:59:59', 0, 0, 1, 1),
  -- 商家券（满 50 减 10，不可叠加，限店铺 1）
  ('满 50 减 10 商家券', 2, 2, 1,  1,  5000, 1000, 100,   0,    0,   500,   500, 1, 1, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 0, 0, 0, 1),
  -- 商家折扣券（8.8 折封顶 20，不可叠加，限店铺 2）
  ('全场 8.8 折（封顶 20）', 2, 2, 2, 3, 3000,    0,  88, 2000,    0,   300,   300, 1, 2, NULL, NULL, 30, 0, 0, 1),
  -- 会员月度红包：银卡及以上，每月 1 张
  ('会员月度 10 元红包', 3, 3, 0,  2,   0, 1000, 100,    0,    0,    -1,    -1, 1, 2, NULL, NULL, 30, 1, 1, 1),
  -- 黑卡专享
  ('黑卡专享 8 折（封顶 30）', 3, 3, 0, 3,  0,    0,  80, 3000,    0,    -1,    -1, 1, 2, NULL, NULL, 30, 3, 0, 1),
  -- 原秒杀券迁移示例（付费购买型）
  ('限时抢购 100 元代金券', 2, 1, 1, 1, 0, 10000, 100,   0, 5000,   200,   200, 1, 1, '2026-10-20 10:00:00', '2026-10-31 23:59:59', 0, 0, 0, 1);

-- 知识库种子数据（真实客服高频问题）
INSERT INTO `tb_knowledge_doc` (`category`, `title`, `question`, `content`, `keywords`, `priority`) VALUES
('券使用', '优惠券为什么用不了', '我的优惠券怎么用不了',
 '优惠券用不了通常是四种原因：1）未达到使用门槛，例如满 100 减 30 的券，订单需满 100 元；2）券已过期；3）该券限指定店铺使用，当前店铺不在范围内；4）同一订单中与其他不可叠加的券互斥，一单只能用一张。你可以在「我的-优惠券」中查看每张券的适用条件和有效期。',
 '用不了,不能用,无法使用,不可用,失效', 10),
('券使用', '红包和优惠券的区别', '红包和优惠券有什么区别',
 '红包是平台发放的现金抵扣，通常无门槛、全平台通用；优惠券是商家发放的折扣或满减，一般限指定店铺使用；会员红包是会员权益，按会员等级每月自动发放。三者可以叠加使用（标记为可叠加的券除外）。',
 '红包,优惠券,区别,会员红包', 9),
('券使用', '优惠券过期能否补发', '券过期了能补吗',
 '优惠券过期后无法恢复使用，也不支持补发。建议在到期前 3 天留意 App 内的到期提醒。会员月度红包若因账号异常未到账，可提交工单由客服核实补发。',
 '过期,补发,恢复,到期', 8),
('退款规则', '优惠券订单退款后券是否退回', '退款了券会退回来吗',
 '订单退款成功后，已使用的优惠券会在 24 小时内退回账户，有效期沿用原券的剩余有效期，不会延长。若券在退款时已过期，则不再退回。',
 '退款,退券,退回,返券', 10),
('退款规则', '退款多久到账', '退款几天到账',
 '退款提交后，微信/支付宝原路退回一般 1-3 个工作日到账，银行卡 3-7 个工作日。若超时未到账，请提供订单号联系人工客服。',
 '退款,到账,多久,几天', 9),
('会员权益', '会员等级怎么升级', '会员怎么升级',
 '会员等级由成长值决定：银卡 500、金卡 2000、黑卡 8000。消费每 1 元获得 1 点成长值，签到、评价、邀请好友也可获得成长值。成长值累计计算，不会因等级提升而清零。',
 '会员,升级,成长值,等级', 9),
('会员权益', '会员红包什么时候发', '会员红包每月几号发',
 '会员红包在每月 1 日 00:30 自动发放到账户，有效期 30 天。若当日未到账，通常是等级变动或账号状态异常，可次日再查看或联系客服。',
 '会员红包,发放,几号,每月', 9),
('营业信息', '如何查询商家营业时间', '这家店几点开门',
 '商家营业时间显示在店铺详情页顶部。节假日营业时间可能调整，建议前往前先致电确认，电话号码同样在详情页。',
 '营业时间,几点,开门,关门', 7),
('发票', '如何开发票', '怎么开发票',
 '在「我的-订单」中选择已完成订单，点击「申请发票」，填写抬头与税号，电子发票会在 3 个工作日内发送至预留邮箱。目前仅支持已完成且未退款的订单。',
 '发票,开票,税号,抬头', 8);

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 验证语句（执行后可跑一遍确认）
-- =====================================================================
-- SELECT COUNT(*) AS 券模板 FROM tb_coupon_template;
-- SELECT COUNT(*) AS 会员等级 FROM tb_member_level;
-- SELECT COUNT(*) AS 知识库 FROM tb_knowledge_doc;
-- SHOW TABLES LIKE 'tb_%';
