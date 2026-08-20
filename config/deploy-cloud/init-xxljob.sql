-- ============================================================
-- P-D20: xxl-job 调度修复（部署后执行，中间件机 mysql 3306）
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' < init-xxljob.sql
-- 内容：1) 补建缺失执行器组 2) 修正错配任务 3) 补建缺失任务
-- 注意：执行器组 ID 依赖 auto_increment，若已存在同名组请先核对/去重
-- ============================================================

-- ---------- 1) 补建缺失执行器组（order/cart/coupon/home/search） ----------
-- 端口与各服务 application.yml 实测一致（9991/9993/9995/9994/9997）
INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-order', 'order 执行器', 0, '21.130.247.89:9991', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-order');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-cart', 'cart 执行器', 0, '21.130.247.89:9993', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-cart');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-coupon', 'coupon 执行器', 0, '21.130.247.89:9995', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-coupon');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-home', 'home 执行器', 0, '21.130.247.89:9994', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-home');

INSERT INTO xxl_job.xxl_job_group (app_name, title, address_type, address_list, update_time)
SELECT 'my-xhs-search', 'search 执行器', 0, '21.130.247.89:9997', NOW()
WHERE NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-search');

-- ---------- 2) 修正挂在 sample 组(NULL 地址)的任务 → 正确组 ----------
-- sample 组 = 1；order 组/… 用子查询取新组 ID（与 address_list 匹配防错配）
UPDATE xxl_job.xxl_job_info SET job_group =
  (SELECT id FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-order')
WHERE job_group = 1 AND executor_handler IN ('orderCloseJob','localMessageRetryJob','deadLetterScanJob','orderMappingRepairJob');

UPDATE xxl_job.xxl_job_info SET job_group =
  (SELECT id FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-inventory')
WHERE job_group = 1 AND executor_handler = 'inventoryReconcileJob';

UPDATE xxl_job.xxl_job_info SET job_group =
  (SELECT id FROM xxl_job.xxl_job_group WHERE app_name='my-xhs-coupon')
WHERE job_group = 1 AND executor_handler = 'couponReconcileJob';

-- ---------- 3) 补建缺失任务（cron 以代码语义为准，可后续在 admin 调整） ----------
-- 券过期扫描（coupon，每 5 分钟）
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '优惠券过期扫描', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */5 * * * ?', 'DO_NOTHING', 'FIRST', 'couponExpireJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-coupon'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='couponExpireJob');

-- 购物车对账（cart，每小时）
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '购物车 Redis/MySQL 对账', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'cartReconcileJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-cart'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='cartReconcileJob');

-- feed 清理（home，每天 3 点）
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, 'Feed 流数据清理', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 3 * * ?', 'DO_NOTHING', 'FIRST', 'feedCleanupJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-home'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='feedCleanupJob');

-- 推荐特征/热池/协同（search，每小时 / 每天）
INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐特征索引更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 * * * ?', 'DO_NOTHING', 'FIRST', 'recommendFeatureJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendFeatureJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐热池更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 */10 * * * ?', 'DO_NOTHING', 'FIRST', 'recommendHotPoolJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendHotPoolJob');

INSERT INTO xxl_job.xxl_job_info (job_group, job_desc, add_time, update_time, author, alarm_email, schedule_type, schedule_conf, misfire_strategy, executor_route_strategy, executor_handler, executor_param, executor_block_strategy, executor_timeout, executor_fail_retry_count, glue_type, glue_source, glue_remark, glue_updatetime, child_jobid, trigger_status, trigger_last_time, trigger_next_time)
SELECT g.id, '推荐 ItemCF 更新', NOW(), NOW(), 'my-xhs', '', 'CRON', '0 0 2 * * ?', 'DO_NOTHING', 'FIRST', 'recommendItemCFJob', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', NOW(), '', 0, 0, 0
FROM xxl_job.xxl_job_group g WHERE g.app_name='my-xhs-search'
AND NOT EXISTS (SELECT 1 FROM xxl_job.xxl_job_info WHERE executor_handler='recommendItemCFJob');

-- 验证
SELECT g.app_name, i.executor_handler, i.schedule_conf, i.trigger_status
FROM xxl_job.xxl_job_info i JOIN xxl_job.xxl_job_group g ON i.job_group = g.id
ORDER BY g.app_name, i.executor_handler;
