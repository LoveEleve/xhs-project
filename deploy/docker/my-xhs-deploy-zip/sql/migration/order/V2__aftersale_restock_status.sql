-- 售后单补充"库存回补状态"：跨服务回补失败可被恢复任务重试
-- 背景：退款成功后由订单域 Feign 调库存域回补库存；若库存域整体不可用（请求未到达），
--       库存域内部的补偿表不会留下记录 → 永久漏补。故在售后单上记录回补状态，由恢复任务兜底重试。
-- 说明：一次性迁移；全新环境由 init-all.sql 直接建出带列版本。
ALTER TABLE t_aftersale
    ADD COLUMN restock_status TINYINT NOT NULL DEFAULT 0 COMMENT '库存回补状态：0-待回补 1-已回补' AFTER refund_no;
