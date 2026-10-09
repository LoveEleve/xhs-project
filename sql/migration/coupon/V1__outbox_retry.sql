-- Coupon Outbox 补发重试退避（P2/2026-09-27）
-- 背景：OutboxSenderJob 原为固定 5s 轮询、发送失败无限重试——Broker 故障期间每 5s 捶打（每轮 200 条）。
-- 变更：新增 retry_count / next_retry_time；Job 失败后按 30s→480s 指数退避 + ±20% 抖动写回，
--       查询侧加 (next_retry_time IS NULL OR <= NOW()) 条件。
ALTER TABLE t_coupon_outbox
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT '补发失败次数（指数退避+抖动）' AFTER status,
    ADD COLUMN next_retry_time DATETIME NULL COMMENT '下次可补发时间（NULL=立即可发）' AFTER retry_count;

ALTER TABLE t_coupon_outbox
    ADD INDEX idx_status_next_retry (status, next_retry_time);
