-- C-016：t_payment 补渠道交易号（客户端拉起支付/对账/排查的渠道句柄）
-- 背景：tradeCreate 返回的 tradeNo 原只打日志——响应契约缺"预支付参数"，渠道交易号也未落库
ALTER TABLE t_payment
    ADD COLUMN trade_no VARCHAR(64) DEFAULT NULL COMMENT '渠道交易号（支付宝 trade_no；Mock=paymentNo）' AFTER payment_no;
