-- C-008 R7：t_inventory_outbox 增加 event_time 列
-- 目的：Job 补发精确复用原始 eventTime（原实现用 created_at 作代理——应用时钟 T0 vs DB 时钟秒级截断不等，
-- 导致消费端事件级幂等（inbox PK 含 event_time）对补发 MISS → 重复应用窗口）。
-- 兼容：存量 pending 行为 NULL，Job 回退用 created_at（与旧行为一致，下一轮发送后不再产生新 NULL 行）。
ALTER TABLE t_inventory_outbox
    ADD COLUMN event_time BIGINT NULL COMMENT '原始事件时间戳ms（C-008 R7：补发复用，保证事件级幂等键一致）' AFTER action;
