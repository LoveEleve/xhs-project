-- 结算域：3 张表 + 2 个日结/对账查询索引（一次性迁移；全新环境由 init-all.sql 直接建出）
-- 背景：日结账单按 (渠道, 日期) 聚合支付/退款，原表无对应索引 → 全表扫描，数据量上来后账单生成会拖垮库。

CREATE TABLE IF NOT EXISTS t_settlement_bill (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    bill_date       DATE          NOT NULL COMMENT '账单日期（T-1 自然日）',
    channel         TINYINT       NOT NULL COMMENT '渠道：1-支付宝 2-微信 99-Mock',
    pay_count       INT           NOT NULL DEFAULT 0 COMMENT '成功收款笔数',
    pay_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '成功收款金额',
    refund_count    INT           NOT NULL DEFAULT 0 COMMENT '成功退款笔数',
    refund_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '成功退款金额',
    net_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '净额=收款-退款',
    fee_rate        DECIMAL(6,4)  NOT NULL DEFAULT 0 COMMENT '手续费率（如 0.0060）',
    fee_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '手续费=净额×费率',
    settle_amount   DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '应结算=净额-手续费',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-初始 1-已生成 2-已对账 3-有差异 4-作废',
    run_no          INT           NOT NULL DEFAULT 0 COMMENT '重跑次数',
    diff_count      INT           NOT NULL DEFAULT 0 COMMENT '差异笔数（对账后回填）',
    diff_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '差异金额合计（本地-渠道）',
    generated_at    DATETIME      DEFAULT NULL COMMENT '生成时间',
    reconciled_at   DATETIME      DEFAULT NULL COMMENT '对账时间',
    remark          VARCHAR(256)  DEFAULT NULL COMMENT '备注',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_date_channel (bill_date, channel),
    INDEX idx_bill_date (bill_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结算日账单（日切+幂等重跑）';

CREATE TABLE IF NOT EXISTS t_settlement_diff (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    bill_date       DATE          NOT NULL COMMENT '账单日期',
    channel         TINYINT       NOT NULL COMMENT '渠道',
    biz_type        TINYINT       NOT NULL COMMENT '业务类型：1-支付 2-退款',
    diff_type       TINYINT       NOT NULL COMMENT '差异类型：1-本地有渠道无 2-渠道有本地无 3-金额不一致',
    local_no        VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '本地单号（支付/退款单号）',
    channel_no      VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '渠道流水号',
    local_amount    DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '本地金额',
    channel_amount  DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '渠道金额',
    diff_amount     DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '差异金额=本地-渠道',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待处理 1-已处理 2-已忽略（自动收敛）',
    handle_remark   VARCHAR(256)  DEFAULT NULL COMMENT '处理说明',
    handled_at      DATETIME      DEFAULT NULL COMMENT '处理时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_diff_key (bill_date, channel, biz_type, diff_type, local_no, channel_no),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='结算对账差异（挂账）';

CREATE TABLE IF NOT EXISTS t_channel_flow (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    bill_date       DATE          NOT NULL COMMENT '账单日期',
    channel         TINYINT       NOT NULL COMMENT '渠道',
    channel_no      VARCHAR(64)   NOT NULL COMMENT '渠道流水号（对账单内唯一）',
    biz_type        TINYINT       NOT NULL COMMENT '业务类型：1-支付 2-退款',
    local_no        VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '渠道回传的商户单号（我方支付/退款单号）',
    amount          DECIMAL(12,2) NOT NULL COMMENT '金额',
    trade_time      DATETIME      NOT NULL COMMENT '渠道交易时间',
    source          VARCHAR(32)   NOT NULL DEFAULT 'import' COMMENT '来源：import-导入 simulate-演练生成',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_no (channel, channel_no),
    INDEX idx_bill (bill_date, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道流水（对账文件落地）';

ALTER TABLE t_payment ADD INDEX idx_pay_type_paid_at (pay_type, paid_at);
ALTER TABLE t_refund  ADD INDEX idx_status_success_at (status, success_at);
