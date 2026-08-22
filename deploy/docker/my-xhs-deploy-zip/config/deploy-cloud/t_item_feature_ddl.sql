-- ============================================================
-- A-1 修复: t_item_feature 补建 DDL（推荐系统依赖，P1）
-- 适用: 已部署存量环境（init-all.sql 已含此表, 新环境无需单独执行）
-- 执行: mysql -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' < t_item_feature_ddl.sql
-- 验证: SELECT COUNT(*) FROM my_xhs_content.t_item_feature;
--        触发 xxl#19(recommendFeatureJob) 后应有行且无错误日志
-- ============================================================

USE my_xhs_content;

CREATE TABLE IF NOT EXISTS t_item_feature (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT 'ID',
    note_id       BIGINT        NOT NULL COMMENT '笔记ID',
    tags          VARCHAR(512)  DEFAULT NULL COMMENT '标签列表(JSON)',
    category      VARCHAR(64)   DEFAULT NULL COMMENT '品类(空串=unknown, 精排/打散依赖)',
    quality_score DOUBLE        NOT NULL DEFAULT 0 COMMENT '精排质量分',
    like_count    INT           NOT NULL DEFAULT 0 COMMENT '点赞数',
    comment_count INT           NOT NULL DEFAULT 0 COMMENT '评论数',
    geo_hash      VARCHAR(16)   DEFAULT NULL COMMENT '地理位置hash(GEO召回)',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_note_id (note_id),
    KEY idx_category (category),
    KEY idx_quality_score (quality_score),
    KEY idx_geo_hash (geo_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推荐特征索引表(推荐系统)';
