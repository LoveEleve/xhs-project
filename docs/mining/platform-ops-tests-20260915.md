# 平台运维与测试盲区（2026-09-15）

## 1. 环境盘点（config/production-env-config/docker-container-review-20260811-162334/）
- 12 层目录：docker-info / networks / volumes / images / containers / daemon / deploy-src / **runtime-queries** / host-system / container-runtime / business / effective-config
- runtime-queries 抓了 10+ 项运行态实况：mysql 主从、redis 主从、sentinel、rocketmq、nacos（configs/services）、prometheus、grafana、elasticsearch 等
- 样例事实：RocketMQ `DefaultCluster / broker-a`、版本 `V5_1_4`；存在系统重试主题 `%RETRY%DLQ_MONITOR_*`
- business 目录：远程微服务清单、xxl-job 任务、mysql/redis/rocketmq 深查、nacos 安全

## 2. 部署踩坑（DEPLOY-NOTES.md，挑 10/15+）
1. 部署包必须最新（330 文件）；zip 为根目录结构，解压根直接 `docker compose up -d`
2. 覆盖升级：卷名跟随 compose 项目名，项目名不变则数据复用
3. 前置：`systemctl enable docker`（原为 disabled，开机不自启）
4. compose `depends_on` 写空映射会报错
5. RocketMQ 5.1.4 官方镜像无内置 Prometheus exporter（另建 textfile/metrics）
6. `broker.conf` 禁止行内注释（`#` 后内容会被当值吃掉，SYNC_FLUSH 曾静默回退）
7. 加固：SYNC_FLUSH + `autoCreateTopicEnable=false`
8. MySQL 8.0.46 无 `Innodb_deadlocks` 状态变量（监控另取行锁等待）
9. 复制监控需独立从库 exporter（3307）；从库 1236/open relay log 修复流程
10. `docker exec` 跑中文 SQL 需指定字符集；healthcheck 镜像缺 curl（改用 wget / `/dev/tcp`）

## 3. 迁移脚本（deploy/docker/my-xhs-deploy-zip/sql/migration/，6 个）
- order / inventory / content / user 的 V1__init
- content V2__add_push_progress（Feed 断点续推：push_status/push_cursor/push_total + 索引）
- cart V1__cart_event_uk_msg_id（事件流水 msg_id 唯一索引，幂等兜底）

## 4. 服务单测盘点（未验证全绿，暂不写简历）
- 平台 13 个服务共 **236 个 @Test 方法**：common 6 类、order 5、coupon 4、inventory 4、search 4、analytics 3、product 3、user 3、cart 2、content 2、home 2、counter 1、payment 1
- 旧 AI 模块另有 55 个测试类（零参考，不计）
- 覆盖率：仓库有 GitLab CI test 阶段与 JaCoCo 产物声明，但 pom 未配 JaCoCo、无阈值门禁 → 覆盖率实际未采集

## 5. 运行态验证文档
- `docs/test-3/F-037-RUNTIME-VERIFY.md`：验证"库存补偿写回实际预扣桶而非 bucket0"（选 bucketCount>1 的 SKU，比对 `inventory:bucket:count` 与该桶值）——补偿桶归属类追问的实证材料
