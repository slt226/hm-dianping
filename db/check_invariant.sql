-- ============================================================
-- 压测不变式核对（任务 5 · 5.2）
-- 用法：每轮压测结束后立刻执行，把三组数字抄进报告。
--       mysql --defaults-extra-file=... -t hmdp < db/check_invariant.sql
-- ============================================================
-- 为什么只读：这份脚本要在每轮之间反复跑，一旦带写操作，
-- 就成了「在测量过程中改变被测对象」，数据全部作废。
-- Redis 部分无法在 mysql 客户端里执行，见文件末尾的注释。

-- ① DB 侧：权威库存的滞后副本
SELECT
    v.voucher_id,
    v.stock                                   AS db_stock,
    (SELECT COUNT(*) FROM tb_voucher_order o
      WHERE o.voucher_id = v.voucher_id)       AS orders,
    (SELECT COUNT(*) FROM tb_seckill_dead_letter d
      WHERE d.voucher_id = v.voucher_id AND d.status = 0) AS pending_dead_letter,
    v.stock + (SELECT COUNT(*) FROM tb_voucher_order o
                WHERE o.voucher_id = v.voucher_id) AS stock_plus_orders
FROM tb_seckill_voucher v
WHERE v.voucher_id = 10;

-- ② 订单状态分布：压测正常时只应该有 status=1（待支付）与极少量的其它状态
SELECT status, COUNT(*) AS cnt
FROM tb_voucher_order
WHERE voucher_id = 10
GROUP BY status;

-- ③ 死信明细（最多 5 条）：非空说明有订单预扣了库存但没落库
SELECT id, order_id, user_id, status, retry_count, reason
FROM tb_seckill_dead_letter
WHERE voucher_id = 10
ORDER BY id DESC
LIMIT 5;

-- ------------------------------------------------------------
-- 【Redis 侧】用 redis-cli 单独跑，三个数字必须与上面 ① 对齐：
--
--   GET   seckill:stock:10        → Redis 剩余库存
--   SCARD seckill:user:10         → 已购用户数（应等于 orders）
--   XLEN / LLEN 不需要看，秒杀走的是普通队列，用下面这条：
--   (management API) /api/queues/%2F/seckill.queue → messages、consumers
--
-- 【不变式】
--   Redis 剩余 = DB 剩余 − 待补偿死信数
--   初始库存 100 时还等价于：DB 剩余 + 订单数 = 100
--
--   两式一起跑，超卖（订单数 > 100）与少卖（Redis 剩余 + 订单数 < 100）
--   会同时露出来。只看其中一个，都会把「死信积压」误判成「没问题」。
-- ------------------------------------------------------------
