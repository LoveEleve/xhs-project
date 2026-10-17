-- =====================================================================
-- V2: 一订单最多一条"活着的"（待支付）支付单 —— uk_order_pending 函数索引
-- （ITER-002 / C-002 阶段①；来源：订单-讲解 06 §七 A1 改造规划）
-- =====================================================================
-- 背景：原模型"单次尝试"（在途 30 分钟拦新），允许多条 pending 并存；
-- 改造后：同渠道复用 / 换渠道关旧开新，结构上保证同订单 pending ≤ 1。
--
-- 执行顺序：先清理存量（同订单多条 pending → 留最新、其余置已关闭 4），后建索引。
-- 幂等：清理 UPDATE 可重复执行；建索引若已存在报 1061（忽略即可）。
-- MySQL 8.0（函数索引；部署基线已核实为 mysql:8.0）。
-- =====================================================================

USE my_xhs_payment;

-- 1) 存量清理：同订单多条 pending → 只留最新一条（MAX(id)），其余置"已关闭(4)"
--    （未支付 ≠ 失败：与换渠道/终结关闭语义统一）
UPDATE t_payment p
JOIN (
    SELECT order_id, MAX(id) AS keep_id
    FROM t_payment
    WHERE status = 0 AND deleted = 0
    GROUP BY order_id
    HAVING COUNT(*) > 1
) d ON p.order_id = d.order_id
SET p.status = 4, p.updated_at = NOW()
WHERE p.status = 0 AND p.deleted = 0 AND p.id <> d.keep_id;

-- 2) 建函数唯一索引：仅对"待支付且未删除"的行约束 order_id 唯一
ALTER TABLE t_payment
    ADD UNIQUE INDEX uk_order_pending ((CASE WHEN status = 0 AND deleted = 0 THEN order_id END));

-- 3) 存量"失败但未核实"标记（GAP-2/4）：改造前的置失败不查渠道 → 标记为未核实(2)，
--    由 PaymentReconcileJob 的核查分支重查渠道（已付 → 收敛；未付 → 补记已核实(1)）
UPDATE t_payment SET verify_method = 2 WHERE status = 2 AND verify_method IS NULL;
