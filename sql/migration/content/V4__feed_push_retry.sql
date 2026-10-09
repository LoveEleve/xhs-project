-- Feed 推送补偿退避（P3/2026-09-27）
-- 背景：FeedMessageRetryJob.compensateIncompletePush 固定 60s 轮询重投，未完成条目每轮都被重投；
--       现改为写回 push_next_retry_time（60s→600s 指数退避 + ±20% 抖动），SQL 侧过滤到期条目。
ALTER TABLE t_local_message
    ADD COLUMN push_next_retry_time DATETIME NULL COMMENT '下次可补偿推送时间（指数退避+抖动，P3/2026-09-27）' AFTER push_total;

ALTER TABLE t_local_message
    ADD INDEX idx_push_status_next_retry (push_status, push_next_retry_time);
