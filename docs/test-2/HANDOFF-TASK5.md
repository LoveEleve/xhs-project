# my-xhs 交接文档 — Task5（监控体系补齐 + 部署包实测合回 + G1 测试闭环 → G2 起）

> 2026-08-13 | 承接 Task4（部署包+8 处修复）→ 本阶段：监控体系系统性补齐、对方试验机实测版合回、G1 全量测试闭环、Review 方法论沉淀。
> 给下一个 AI 的全量交接。**重点：部署包状态（§三）、测试进度（§四）、问题清单（§五）、方法论（§七）。**

---

## 零、状态速览（2026-08-13）

```
微服务 15 个 UP（本机 21.214.97.212）| 中间件 27 容器（试验机 21.130.247.89，对方实测版）
代码修复：21 项闭环（P0/P1/P2 全修 + T-001~025 中 21 项（含 T-025 broker.conf 注释规范））| 监控：9 看板 120 面板 / 28 告警 / 三层采集
测试：G1 全部完成（9 组，核心 107/108 通过，1 个为断言格式非功能缺陷）| G2~G7 未开始（下一步）
部署包：对方实测验证版已合回 master（zip: /data/workspace/my-xhs-deploy-package.zip，27 容器）
Token→/tmp/test_token.txt | .secrets/tokens.env（随机化）| 凭据: Xhs@2026#*
```

## 一、环境拓扑

- **微服务机 = 本机容器 21.214.97.212**（15 JVM，未来迁用户 Ubuntu VM）
- **中间件机 = 21.130.247.89**（27 容器，对方管理，无本机 docker/ssh 权限——**中间件侧改动只能给脚本**）
- **云主机**：用户自购，EIP 决策，部署源 = 最终 zip
- 微服务→中间件走 iptables 白名单（公司机合规要求；云主机用安全组）

## 二、本阶段已完成

### 2.1 监控体系系统性补齐（此前 4 看板 → 9 看板 120 面板）
| 项 | 内容 |
|---|---|
| 三层采集 | 主机(node-exporter 9100) + 中间件(redis/es/mysql/mysql-slave 9105/canal/OAP) + 应用(15 服务) |
| 看板 9 个 | JVM 32 / MySQL 18(含复制+锁) / ES 11 / Node 14 / Redis 12 / MQ 11 / API 9 / Biz 5 / Tomcat 8 |
| 告警 28 条 | 应用 6 + 业务 8 + 黄金信号 7 + MySQL 3 + DLQ 1 + 主机 3（node 高 CPU/内存/磁盘）|
| 修复的看板坑 | P99 用 histogram（代码已开 publishPercentileHistogram 生效✅）；Tomcat 线程 micrometer 1.13+ 无 → JVM 线程替代；业务面板对齐实际埋点；${DS_PROMETHEUS} 替换 |

### 2.2 代码修复闭环（21 项，全部验证）
- P0×4/P1×5/P2×8/O1/O2/P-B4/P-D32/39/42（Task3-4 已完成）
- T-001~025 中 21 项（含 T-025 broker.conf 注释规范）：限流（captcha/register）、缓存头、禁用续期（T-005）、logout 兜底（T-007）、refresh/logout body（T-008）、HMAC 签名升级（T-009/010/011：method\|path\|query\|ts\|nonce\|bodyHash）、改密凭证失效（T-012）、幽灵关注（T-013）、拉黑拦截（T-016）、Feign 拦截器系统性（T-017）、block 序列化（T-018）、旧 access 拉黑（T-019）、缺头 400（T-021）、白名单（T-022）、采样率（T-024）
- **运行态抽测 4/4**：P-B4 旧 token 拒 / T-005 40107 / T-013 10001 / T-016 10010

### 2.3 部署包（对方试验机实测版，已合回 master）
- **27 容器**：26 + mysqld-exporter-slave（9105 从库复制监控）
- 对方实测修正已吸收：镜像（oliver006/redis_exporter）、healthcheck（wget/TCP）、broker.conf 注释独立行（SYNC_FLUSH 实测生效）、SW 采样率 1000（万分比=10%）、${DS_PROMETHEUS} 替换、看板双列布局
- 新增脚本：rocketmq-metrics.sh（textfile 方案，官方 exporter 不兼容 5.1.4）、mysql-deadlock-metrics.sh（死锁监控，已造死锁验证）
- 新文档：DEPLOY-NOTES.md（28 条实测坑）、README-METRICS.md（监控方案）
- **zip 最终版**：/data/workspace/my-xhs-deploy-package.zip（330 文件 2.6M）

## 三、部署包待对方生效项（试验机/云主机）
```bash
docker compose up -d node-exporter mysqld-exporter-slave   # 新增 2 容器
curl -s -X POST http://127.0.0.1:19090/-/reload             # 28 告警
# Grafana provisioning 自动加载 9 看板；node 需挂 /data/rocketmq-textfile + 2 个 cron 脚本
```

## 四、测试进度（主线）

### G1 认证与用户：✅ 完成（docs/test-3/cases/G1-auth-user/）
- 9 个用例文件（G1-01~09）、核心断言 107/108（唯一"失败"=Redis 存储带引号的断言格式，应用层已兼容）；边界补跑通过（04-09/10/12、07-06/08/09、08-05、09-02/06）
- 测试工具：docs/test-3/helpers/testlib.py（签名器 base64+bodyHash、new_user、check）
- 时间矩阵：docs/test-3/cases/00-time-matrix.md（40 项机制主/兜底方法）

### G2 内容与社交：♻️ 需重做（用户 2026-08-13 删除我生成的 G2-01/02/03，由下一个 AI 重新规划）
- **G2 目录仅保留 README.md（我的总览，可留可改），用例文档全删**
- **重做要求**：严格按 G1 模式——先逐模块查代码实证（端点/参数/状态机/Redis key/表结构/MQ topic），写一个用例文件→深度 REVIEW→修正→再下一个；**禁止一次批量生成 + 禁止擅自执行测试**（执行等用户指示）
- **已知素材（我已核实，可直接用）**：
  - 笔记：POST /api/note/publish（body: title/content/images）、POST /api/note/draft、POST /api/note/{id}/publish、DELETE /api/note/{id}（逻辑删除+级联删评论）、GET /api/note/detail/{id}、POST /api/note/batch-detail（P2-3 白名单免签）、GET /api/note/user/{userId}、GET /api/note/my、POST /api/note/{id}/share（仅 PUBLISHED）、POST /api/note/upload/image；**发布返回 data={"noteId":...}（dict 非数字）**；状态码：status=2=PUBLISHED（非 1）、audit_status=1；缓存 myxhs:note:detail:{id}（读时回填）；本地消息表 my_xhs_content.t_local_message（8 列：id/topic/body/status/retry_count/push_status/push_cursor/push_total，topic=FEED_TOPIC）
  - 评论：POST /api/comment（RateLimit 10 次/60s）、GET /api/comment/list/{noteId}、children/{parentId}、count/{noteId}、page/{noteId}；myxhs:comment:list/count
  - 点赞/收藏：POST/DELETE /api/social/like（body LikeRequest{bizType,bizId}）、GET /status、/batch-status、/count；**bizType=1→targetType=1,countType=1（笔记点赞）；bizType=2→targetType=3（评论点赞）；收藏 countType=2**；计数 key myxhs:counter:{targetType}:{targetId}:{countType}；myxhs:like:{targetType}:{targetId}（Set）、myxhs:favorite:{userId}（Set）；表 my_xhs_analytics.t_like/t_favorite；MQ SOCIAL_TOPIC:LIKE/UNLIKE/FAVORITE/UNFAVORITE
  - Feed 链路：发布→本地消息表(FEED_TOPIC)→FeedMessageRetryJob(30s/60s)→MQ→home FeedPushConsumer
  - canal→ES：note_instance→NOTE_INDEX_TOPIC→search NoteIndexSyncConsumer（发笔记后 ES 可见 sleep 1-2s）
- **已验证教训**：发布接口返回 {noteId}；状态码枚举以代码为准；点赞参数是 bizType/bizId；G2 测试会产生 t_note/t_comment 数据，执行后清理

### G3~G7：未开始（下一步 G3 商品与购物车）

## 五、问题清单状态（docs/test-3/review/ISSUES.md）
- ✅ 已修验证 21 项（T-001/002/003/005/007/008/009/010/011/012/013/016/017/018/019/021/022/024/025 + 早期 P/T 系列）
- ⏳ 待业务决策 2 项：T-004（注册枚举，建议统一 10005）、T-006（JWT secret 明文，P-D1 关联）
- 📝 观察 5 项：T-020（gateway PrematureClose 偶发，已缓解）、T-023（并发同 phone 错误码 10002 误导）、T-027（SW Dubbo 误报）、T-028（Lettuce RETEX 显示）、T-029（Redis 调用密集可优化）

## 六、文档地图
| 文档 | 内容 |
|---|---|
| docs/test-3/README.md | test-3 总览（分组方案 G1-G7）|
| docs/test-3/cases/00-time-matrix.md | 时间机制总表（40 项，主/兜底方法）|
| docs/test-3/cases/G1-auth-user/ | G1 九份用例文档（已执行）|
| docs/test-3/helpers/testlib.py | 测试工具库（签名/注册登录/L2 断言）|
| docs/test-3/review/ISSUES.md | T-001~030 问题登记（状态已更新）|
| docs/test-3/MANUAL-VERIFY.md | 人工验证手册（8 控制台操作+预期数据；SW 采样率已生效标注）|
| **docs/test-3/REVIEW-METHODOLOGY.md** | **三层验证法（L0/L1/L2）+ 部署包清单（必读）** |
| docs/test-2/execution/pitfalls.md | 踩坑 #1~#83 |
| config/deploy-cloud/DEPLOY-NOTES.md | 对方 28 条实测坑（部署前必读）|
| config/deploy-cloud/README-METRICS.md | 监控方案（textfile/死锁/从库）|

## 七、方法论（必读）
**docs/test-3/REVIEW-METHODOLOGY.md**——三层验证法：
- L0 静态自洽 / L1 框架语义（查官方文档/registry/源码）/ L2 运行态实测
- **禁止"没问题"**；结论必须标注层级（"L0+L1 通过，L2 待实测"或"实测通过"）
- 部署包四类清单：镜像类（tag/工具/端口）、解析类（Properties 行内注释/ini #）、框架类（采样率万分比/Grafana uid/Prometheus reload/Boot 3 无 tomcat.threads）、数据类（指标名核对）

## 八、给下一个 AI 的执行要点
1. **测试主线**：G2 内容与社交（笔记 CRUD/批量详情 P2-3/评论/点赞/收藏 + FeedMessageRetryJob + canal→ES）——按 G1 模式：写文档→深度 REVIEW→修复→逐用例执行（testlib 复用）+ L2 数据验证
2. **中间件侧**：无权限，只能给脚本（对方实测闭环，多信对方实测）
3. **改 common 后**：rm -rf 全部 target 重建（#80 教训），strings 验证 jar 内 class
4. **Review**：先读 REVIEW-METHODOLOGY，结论分级，禁止"没问题"
5. **Redis 操作**：redis-py（本机无 redis-cli）；RedisOperator 存值带 Jackson 引号（getString 已剥）
6. **测试数据**：统一前缀（g1x_/g2x_），执行后清理（t_user/t_follow/t_user_address/**t_note**/t_comment + Redis 用户相关 key）
