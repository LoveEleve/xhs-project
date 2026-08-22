# A 类修复部署复验清单（对方部署后逐项勾选）

> 2026-08-14 | 背景：A 类 4 项修复已打包（zip 内含），但运行环境未部署——本清单供部署后复验闭环。
> 部署顺序：① 更新部署包 → ② `bash config/deploy-cloud/apply-review-fixes.sh`（中间件机）→ ③ 按下方复验。
> 注：A-4 已修正——canal `serverMode=rocketMQ` 是 producer，11111 不监听属预期，验证改走 RocketMQ。

## 一、中间件机执行（21.130.247.89）

```
# 1. 解压新部署包（或直接使用已更新的 /data/workspace/my-xhs-deploy-zip）
# 2. 执行部署+复验脚本
cd <解压根目录>/config/deploy-cloud
bash apply-review-fixes.sh /data/workspace/my-xhs-deploy-zip
```
脚本输出逐项 PASS/FAIL：
- **A-1** PASS = `t_item_feature` 已存在且 ≥10 字段
- **A-3** PASS = 登录接口非 403（Dashboard 免登录生效）
- **A-4** PASS = canal 容器运行 + RocketMQ 中 canal topic 存在（11111 不监听为预期）
- **A-5** PASS = 0 超时任务数为 0
- **A-6/A-7** = 确认项（输出配置值即可）

## 二、功能复验（建表后）

| 验证点 | 命令/操作 | 预期 |
|---|---|---|
| A-1 表存在 | `SHOW TABLES FROM my_xhs_content LIKE 't_item_feature';` | 1 行 t_item_feature |
| A-1 推荐任务成功 | xxl-job 控制台触发 recommendFeatureJob(xxl#19) 或等下次调度 | 日志无 error；`SELECT COUNT(*) FROM t_item_feature;` > 0 |
| A-1 推荐链路恢复 | 请求推荐 feed | 出现 CONTENT/GEO 来源；category 非 unknown |
| A-3 Dashboard | 浏览器 http://21.130.247.89:18081 | 直接进入控制台，可查 topic/消费进度 |
| A-4 canal→MQ 链路 | `docker exec my-xhs-mq-broker sh mqadmin topicList -n 127.0.0.1:9876 \| grep -E 'NOTE_INDEX\|PRODUCT_INDEX\|INVENTORY_CACHE'` | 3 个 topic 存在；改一条 MySQL 数据后 Dashboard 对应 topic 消息数增加 |
| A-5 超时生效 | `SELECT executor_handler, executor_timeout FROM xxl_job.xxl_job_info;` | 无 0；对账类 300s、其余 60s |

## 三、微服务机侧（21.214.97.212）

| 验证点 | 命令/操作 | 预期 |
|---|---|---|
| A-6 agent 采样率 | 启动参数含 `SW_AGENT_SAMPLE=3000` | SW 中连续请求 trace 命中率 100% |
| A-2 traceId 打通 | 取一条业务日志 traceId，在 SW 检索 | 可关联到同 trace（若采用方案①/②） |

## 四、勾选确认（对方填写）

- [ ] A-1 建表成功，xxl#19 执行成功且 t_item_feature 有数据
- [ ] A-1 推荐 feed 出现 CONTENT/GEO 来源
- [ ] A-3 Dashboard 免登录可进控制台
- [ ] A-4 canal→MQ 3 个 topic 存在且消息持续增加（11111 不监听为预期，勿再测）
- [ ] A-5 全部任务已有超时（无 0）
- [ ] A-6 采样率确认（SW_AGENT_SAMPLE=3000）
- [ ] A-7 Grafana 10 看板面板有数据

> 以上全部勾选即 A 类闭环；B 类（微服务代码）待源码提供后另行修复。
