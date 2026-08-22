-- ============================================================
-- V2: t_local_message 扩展 — Feed 推送进度跟踪
-- 为 P0-3 写扩散 Pipeline 无原子性修复提供断点续推能力
-- ============================================================

-- 扩展 content 模块的 t_local_message 表
-- （content 模块的 t_local_message 用于笔记发布 → MQ → Feed 推送的可靠性保障）

ALTER TABLE t_local_message 
    ADD COLUMN IF NOT EXISTS push_status TINYINT NOT NULL DEFAULT 0 
        COMMENT '推送状态: 0=未推送 1=推送中 2=已推送 3=推送失败',
    ADD COLUMN IF NOT EXISTS push_cursor INT NOT NULL DEFAULT 0 
        COMMENT '推送游标（已推送到第几个粉丝）',
    ADD COLUMN IF NOT EXISTS push_total INT NOT NULL DEFAULT 0 
        COMMENT '总粉丝数（用于进度计算和日志）';

-- 新增索引加速待推送消息扫描
CREATE INDEX IF NOT EXISTS idx_push_status ON t_local_message (push_status, created_at);
