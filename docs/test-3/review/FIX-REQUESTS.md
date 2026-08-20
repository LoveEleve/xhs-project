# 修复提交清单（提交中间件/部署团队 + 本机微服务）

> 2026-08-14 | 来源：G1-G8 全量测试（291 业务用例 + 6 观测用例 + 多轮回归）+ L4 基础设施验证
> 分两类：**A. 中间件/部署团队**（对方机 21.130.247.89，无本机权限——需对方执行）；**B. 微服务代码**（本机已修/待修，随部署包）
> 每项含：现象→实证→修复建议→验证方法

---

## A. 中间件/部署团队修复项（7 项）

### A-1 【部署】t_item_feature 表缺失（T-086/T-083 关联，P1 级功能缺陷）
- **现象**：recommendFeatureJob（xxl#19）特征提取 INSERT 目标表不存在 → 每次执行内部失败但 handle 200"特征提取完成"（误导性成功）
- **影响链**：CONTENT/GEO 两路召回空、精排质量分降级 0.3、category=unknown（品类打散误伤）、用户兴趣标签不更新——推荐系统 4 项功能静默失效
- **实证**：`SHOW TABLES FROM my_xhs_content LIKE 't_item_feature'` 为空；SW 错误 segment=search recommendFeatureJob（近 3h 唯一错误源）；xxl#19 handle 200 但日志 error
- **修复**：执行建表 DDL（列：id/note_id/tags/category/quality_score/like_count/comment_count/created_at/geo_hash，建议随 init-all.sql 补充）
- **验证**：建表后触发 xxl#19 → t_item_feature 有行；推荐 feed 出现 CONTENT/GEO 来源

### A-2 【配置】SkyWalking traceId 与业务日志 traceId 未打通（T-099）
- **现象**：Kibana 日志 traceId（gateway X-Trace-Id UUID）无法关联 SkyWalking trace（SW 自生成 `UUID.段.span`）
- **影响**：排障需两套系统手工对应，日志→trace 一跳缺失
- **修复建议**：SW agent 配置业务 traceId 传播（如 skywalking 日志插件 / sw8 头接入 gateway 的 X-Trace-Id）或 OAP 侧开关；需中间件团队确认 SW agent 版本能力
- **验证**：一条请求的日志 traceId 可在 SW 检索到同 trace

### A-3 【环境】RocketMQ Dashboard 登录 403（环境事实 #14）
- **现象**：http://21.130.247.89:18081 根路径 200，登录 API 403（新版 API 认证问题）
- **影响**：投递类测试降级（Outbox/SQL/真实链路替代）；运维侧无法控制台投递/查看消息详情
- **修复建议**：升级/修复 Dashboard 登录 API（版本兼容）；或开放只读观测账号
- **验证**：登录成功可查 topic/消费进度

### A-4 【网络】Canal 11111 服务端口本机不可达（11112 metrics 正常）
- **现象**：本机 `nc 21.130.247.89:11111` Connection refused；11112（Prometheus metrics）可达且 canal 3 实例 up
- **影响**：无法远程确认 canal 实例状态/日志（测试期靠 ES 文档变化间接验证）
- **修复建议**：确认 canal server 端口映射（容器端口 vs 宿主端口）；开放 11111 或提供状态查询方式
- **验证**：本机可连 11111（或文档化端口映射）

### A-5 【配置】xxl-job executor_timeout 全为 0（时间矩阵 #38 L3 审查）
- **现象**：xxl_job_info.executor_timeout 全部 0（不限时）
- **影响**：任务可能悬挂（如大批量对账卡死无超时回收）
- **修复建议**：设置 executor_timeout=60s（对账类任务可放宽 300s）
- **验证**：超时任务被终止

### A-6 【确认】SW 采样率配置
- **现象**：agent.config 模板 `agent.sample_n_per_3_secs=${SW_AGENT_SAMPLE:-1}`（每 3 秒采样 1 条）——当前实际采样率未知（segment 41 万/天）
- **影响**：若采样率过低，trace 会漏采样（排障盲区）；若过高，存储膨胀
- **修复建议**：确认启动参数实际采样率；建议 100%（=3000 万分比）或按存储预算
- **验证**：连续请求 trace 命中率

### A-7 【确认】Grafana 数据源地址 127.0.0.1:19090
- **现象**：Prometheus 数据源 URL=127.0.0.1:19090（Grafana 容器内回环）
- **影响**：当前可用（同机容器）；若 Grafana 迁移/跨机需改地址
- **修复建议**：改为容器网络别名或服务名（文档化）；确认当前看板数据正常
- **验证**：10 看板面板有数据

---

## B. 微服务代码修复项（本机代码，随部署包——ISSUES.md 已登记）

### B-1 【已修·2026-08-14 晚】推荐系统 4 项（本机已修复验证；A-1 建表后 T-083 恢复执行）
- T-082/T-083/T-087/T-088 已修复并验证（见 ISSUES.md）——本机无源码问题已消除，随部署包交付
- T-082 品类打散过严（同品类第 2 条即跳过——注释"≤2"实现"≥2 continue"）
- T-083 recommendFeatureJob 表缺失仍 handle 200（误导性成功）
- T-087 FOLLOWING 召回恒空（recommend:following:latest 无写入方）
- T-088 product 侧 canal 版本域 es 优先（note 侧已统一 ts）——实测当前 canal 消息无 es 字段未触发，建议对齐

### B-2 【评估：文档化】gateway WS 下游拒绝行为（T-094）——内置组件无法代码修
- 缓解：客户端 PING 超时兜底（应用层 5s 无 PONG 判定失败）——建议写入客户端规范
- access token 冒充 ws_ticket：im 拦截器正确拒绝，但经 gateway 客户端收到 101 死隧道
- 建议：验证 gateway 版本行为或应用层 PING 超时兜底

### B-3 【已修·2026-08-14 晚】counter reconcile 限流优先鉴权（T-095）——perUser 已修（本机）
- @RateLimit 无 perUser → 全局 2 次/分钟共享；且限流先于 isAdminCall（未鉴权请求可消耗额度）

### B-4 【观察项】（不影响功能，记录）
- T-084 Feed 脏成员 500 / T-085 hasMore 取满语义 / T-089 热搜快照重复行 / T-090 home jar 943MB / T-092 ES likeCount 恒 0 / T-093 收件箱残留分页少条 / T-096 is_read 未用 / T-097 seqNo 空洞 / T-098 聚合标题 count 滞后（从库读竞态）

### B-5 【已修】（本机已修复并验证，随部署包——含 B-1/B-3 本轮修复）
- T-081 home 聚合 String→Number 强转（P1，已打包重启验证）
- T-091 删除标记无 ExternalGte version 乱序覆盖（P1，search 已打包重启验证）
- T-058 couponExpireJob SQL / T-059 gateway 404 / T-060~079（G4/G5 修复，Task8 已交付）

---

## 提交优先级建议
1. **A-1 t_item_feature 建表**（P1——推荐系统 4 项功能依赖，DDL 一行可解决）
2. **A-2 SW traceId 打通**（排障效率）
3. **A-3 RocketMQ Dashboard**（运维/测试便利）
4. **A-4 Canal 端口**（可观测性）
5. **A-5/A-6/A-7**（配置加固/确认）
6. **B-1/B-2/B-3**（微服务代码，随下次部署包）

> 全部问题的完整登记见 `docs/test-3/review/ISSUES.md`（T-001~T-099）
