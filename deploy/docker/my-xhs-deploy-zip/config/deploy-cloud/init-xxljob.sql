-- ============================================================
-- 全量初始化 XXL-Job 执行器组与任务（幂等，可重复执行）
-- 覆盖: order/cart/coupon/home/search/inventory/notification/analytics/counter/payment
-- 执行: docker exec -i my-xhs-mysql mysql -uroot -p'Xhs@2026#MySQL' < xxl-job-init-full.sql
-- ============================================================

-- ---------- 1) 执行器组（app_name 与各服务 executor 注册名一致） ----------
INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-order', 'order', 0, '192.168.0.142:9991', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-order');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-payment', 'payment', 0, '192.168.0.142:9992', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-payment');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-cart', 'cart', 0, '192.168.0.142:9993', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-cart');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-home', 'home', 0, '192.168.0.142:9994', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-home');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-coupon', 'coupon', 0, '192.168.0.142:9995', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-coupon');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-inventory', 'inventory', 0, '192.168.0.142:9996', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-inventory');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-search', 'search', 0, '192.168.0.142:9997', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-search');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-counter', 'counter', 0, '192.168.0.142:9998', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-counter');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-analytics', 'analytics', 0, '192.168.0.142:9999', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-analytics');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-notification', 'notification', 0, '192.168.0.142:9990', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-notification');

-- ---------- 2) 任务定义（cron 以各 handler 语义为准） ----------
-- order
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '订单超时关单兜底', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 * * * * ?', 'DO_NOTHING', 'FIRST', 'orderCloseJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-order'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='orderCloseJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '本地消息重试投递', NOW(), NOW(), 'my-xhs', '', 'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST', 'localMessageRetryJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-order'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='localMessageRetryJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '本地死信扫描', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'deadLetterScanJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-order'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='deadLetterScanJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '订单号映射修复', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */5 * * * ?', 'DO_NOTHING', 'FIRST', 'orderMappingRepairJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-order'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='orderMappingRepairJob');

-- payment
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '支付超时检查', NOW(), NOW(), 'my-xhs', '', 'CRON', '0/30 * * * * ?', 'DO_NOTHING', 'FIRST', 'paymentTimeoutCheckJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-payment'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='paymentTimeoutCheckJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '退款超时检查', NOW(), NOW(), 'my-xhs', '', 'CRON', '0/60 * * * * ?', 'DO_NOTHING', 'FIRST', 'refundTimeoutCheckJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-payment'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='refundTimeoutCheckJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '支付成功通知补偿', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0/2 * * * ?', 'DO_NOTHING', 'FIRST', 'paymentNotifyCompensateJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-payment'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='paymentNotifyCompensateJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '退款成功通知补偿', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0/3 * * * ?', 'DO_NOTHING', 'FIRST', 'refundNotifyCompensateJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-payment'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='refundNotifyCompensateJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '支付对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST', 'paymentReconcileJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-payment'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='paymentReconcileJob');

-- inventory
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '库存对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'inventoryReconcileJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-inventory'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='inventoryReconcileJob');

-- coupon
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '优惠券过期扫描', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */5 * * * ?', 'DO_NOTHING', 'FIRST', 'couponExpireJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-coupon'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='couponExpireJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '优惠券对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'couponReconcileJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-coupon'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='couponReconcileJob');

-- cart
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '购物车 Redis/MySQL 对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'cartReconcileJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-cart'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='cartReconcileJob');

-- home
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'Feed 流数据清理', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST', 'feedCleanupJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-home'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='feedCleanupJob');

-- search
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐特征索引更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'recommendFeatureJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendFeatureJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐热池更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */10 * * * ?', 'DO_NOTHING', 'FIRST', 'recommendHotPoolJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendHotPoolJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐 ItemCF 更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FIRST', 'recommendItemCFJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendItemCFJob');

-- analytics
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '关注计数修复', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */10 * * * ?', 'DO_NOTHING', 'FIRST', 'followCounterRepairJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-analytics'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='followCounterRepairJob');

-- counter
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '计数对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'counterReconcileJob', '', 'SERIAL_EXECUTION', 300, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-counter'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='counterReconcileJob');

-- notification
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '未读数对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */10 * * * ?', 'DO_NOTHING', 'FIRST', 'unreadReconcileJob', '', 'SERIAL_EXECUTION', 60, 0, 'BEAN', '', '', NOW(), '', 1, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-notification'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='unreadReconcileJob');

-- ---------- 3) 校验 ----------
SELECT g.app_name, i.executor_handler, i.schedule_conf, i.trigger_status
FROM xxl_job.xxl_job_info i JOIN xxl_job.xxl_job_group g ON i.job_group = g.id
ORDER BY g.app_name, i.executor_handler;
