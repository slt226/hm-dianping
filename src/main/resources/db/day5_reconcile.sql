-- =====================================================================
-- Day 5.4  券体系对账 SQL
-- ---------------------------------------------------------------------
-- 用法：把整份文件丢给 mysql 客户端执行，或逐条 copy 到 Navicat 里跑。
--
-- 核心思想：对账不是「查数据」，是「查不一致」。
-- 每条 SQL 的设计原则都是 —— **正常情况下应该查不出任何一行**。
-- 只要出现结果，就说明有不变量被破坏了，需要人去看。
--
-- 所以别把这些 SQL 的结果集当成报表看，要把它当成告警：
-- 空结果 = 健康，有结果 = 有问题。
-- =====================================================================

USE hmdp;


-- ---------------------------------------------------------------------
-- 检查 1：单笔订单的金额守恒
-- ---------------------------------------------------------------------
-- 不变量：券抵扣总和 + 实付 = 订单原价
--
--   10000(原价) - 1500(券1) - 1000(券2) - 7500(实付) = 0
--
-- 为什么能这么算：pay_amount 只在第一张券的流水里写非零值，
-- 其余行都是 0，所以 SUM(pay_amount) 恰好等于这笔订单的真实实付。
-- 如果当初每行都写了总额，这条 SQL 会算出 3 倍的钱，对账就没意义了。
--
-- 出现非 0 的 diff，通常是：
--   * 核销时 eachCut 记成了券面值而不是实际抵扣（被 Math.max(pay,1) 截断过）
--   * 流水写了一半失败但事务没回滚
-- ---------------------------------------------------------------------
SELECT order_id,
       MAX(order_amount)                                          AS order_amount,
       SUM(discount_amount)                                       AS total_discount,
       SUM(pay_amount)                                            AS total_pay,
       MAX(order_amount) - SUM(discount_amount) - SUM(pay_amount) AS diff
FROM tb_coupon_use_log
GROUP BY order_id
HAVING diff <> 0;


-- ---------------------------------------------------------------------
-- 检查 2：券状态与核销流水必须互相印证
-- ---------------------------------------------------------------------
-- 这是两个方向都必须成立的约束，所以要查两次。

-- 2.1 有流水的券，状态必须是「已使用」
--     有流水却还是未使用 —— 说明退券时删了状态没退，或者核销写完流水后
--     券状态被别的地方改回去了。这张券现在能被重复使用，是资损漏洞。
SELECT uc.id           AS user_coupon_id,
       uc.user_id,
       uc.status,
       uc.order_id     AS coupon_order_id,
       l.order_id      AS log_order_id,
       uc.expire_time
FROM tb_user_coupon uc
         JOIN tb_coupon_use_log l ON l.user_coupon_id = uc.id
WHERE uc.status <> 1;

-- 2.2 状态是「已使用」的券，必须有流水
--     反过来漏了流水 —— 账上有扣减记录不下来的券，
--     用户取消订单时按流水反查就会漏退这张。
SELECT uc.id           AS user_coupon_id,
       uc.user_id,
       uc.status,
       uc.order_id,
       uc.use_time
FROM tb_user_coupon uc
         LEFT JOIN tb_coupon_use_log l ON l.user_coupon_id = uc.id
WHERE uc.status = 1
  AND l.id IS NULL;


-- ---------------------------------------------------------------------
-- 检查 3：库存守恒
-- ---------------------------------------------------------------------
-- 不变量：总发行量 - 剩余库存 = 实际发放张数
--
-- 不限量券（total_stock = -1）必须排除在外：
-- 它的 remain_stock 恒为 -1，不参与任何增减，套进这个公式会算出负数。
--
-- 出现偏差通常意味着：
--   * 扣库存成功但插券失败，补偿没跑（库存被白扣）
--   * 插券成功但扣库存没执行
--   * 退券时误加了库存（退券不该动库存，券本来就已经发出去了）
-- ---------------------------------------------------------------------
SELECT t.id                                        AS template_id,
       t.name,
       t.total_stock,
       t.remain_stock,
       (t.total_stock - t.remain_stock)            AS consumed,
       COUNT(uc.id)                                AS issued,
       (t.total_stock - t.remain_stock) - COUNT(uc.id) AS diff
FROM tb_coupon_template t
         LEFT JOIN tb_user_coupon uc ON uc.template_id = t.id
WHERE t.total_stock <> -1
GROUP BY t.id, t.name, t.total_stock, t.remain_stock
HAVING diff <> 0;


-- ---------------------------------------------------------------------
-- 附加：退券后的残留检查
-- ---------------------------------------------------------------------
-- 退券成功时，券应该变回「未使用」，且 use_time / order_id 都被清空。
-- 这里查的是「状态退回去了、但痕迹没清干净」的券 ——
-- 它们看起来正常，但 order_id 还挂着上一笔订单，会让按订单反查的逻辑出错。
SELECT id,
       user_id,
       status,
       use_time,
       order_id
FROM tb_user_coupon
WHERE status = 0
  AND (use_time IS NOT NULL OR order_id IS NOT NULL);


-- =====================================================================
-- 附：日常巡检用的正向统计（这几条是出报表的，不是告警）
-- =====================================================================

-- 每张模板的发放与使用概况
SELECT t.id                                        AS template_id,
       t.name,
       t.total_stock,
       t.remain_stock,
       COUNT(uc.id)                                AS issued,
       SUM(CASE WHEN uc.status = 0 THEN 1 ELSE 0 END) AS unused,
       SUM(CASE WHEN uc.status = 1 THEN 1 ELSE 0 END) AS used,
       SUM(CASE WHEN uc.status = 2 THEN 1 ELSE 0 END) AS expired
FROM tb_coupon_template t
         LEFT JOIN tb_user_coupon uc ON uc.template_id = t.id
GROUP BY t.id, t.name, t.total_stock, t.remain_stock
ORDER BY issued DESC;
