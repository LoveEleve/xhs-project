# SkyWalking Agent 接入与验证（2026-09-18）

> 背景：OAP/UI/专用 ES 一直在运行，但 release 流程未挂 `-javaagent`，15 服务无实时 trace（xhs/51 题已如实标注）。
> 本次完成接入：agent 落地 + 启动链路改造 + 全量发布 + 端到端验证。

## 一、Agent 落地

- 版本：**9.7.0**（与 OAP 9.7.0 对齐），来源 TUNA 镜像（archive 源 20KB/s → TUNA 1MB/s）
- 位置：`/data/workspace/skywalking-agent-9.7.0/`（52MB，**仓库外**，不入 git）
- 插件适配（Spring Boot 3 / WebFlux）：
  - 移出 `plugins/`：`apm-springmvc-annotation-3.x/4.x/5.x`（与 6.x 冲突）
  - 移入 `plugins/`：`apm-springmvc-annotation-6.x`、`apm-spring-webflux-6.x`、`apm-spring-cloud-gateway-4.x`（原在 optional-plugins）
  - 清理 macOS 元数据 `._*.jar`（否则 AgentClassLoader 报 "jar file can't be resolved"）
- 关键配置经启动参数注入（不改 agent.config）：`service_name`、`collector.backend_service=192.168.0.142:11800`、`logging.dir=/tmp/sw-logs/{module}`、`SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins`

## 二、启动链路改造

- `scripts/release-service.sh`：新增 agent 自动探测与挂载
  - `SW_AGENT_DIR`（默认 `/data/workspace/skywalking-agent-9.7.0`）、`SW_COLLECTOR`（默认本机 11800）、`SW_AGENT_DISABLED=1` 可关闭
  - 发布日志打印 `[SW] SkyWalking agent: service=my-xhs-<module> ...` 可审计
- `scripts/restart-service.sh`：collector 从云上 `21.130.247.89:11800` 修正为本机 `192.168.0.142:11800`，同样支持 agent 挂载

## 三、验证证据

| 验证项 | 结果 |
|---|---|
| 全量发布 | 15/15 服务带 agent 发布成功、健康 200 |
| 服务注册 | OAP `getAllServices`：**15 个 my-xhs 服务全部在列**；依赖 peers（MySQL 3306/3307、Redis 6379/6380/26379、ES 19200）也被插件采集 |
| 金丝雀（cart） | 发布后 5 分钟 `sw_segment-20260918` 中 cart 段 **181** 个 |
| 跨进程链路 | 真实登录（captcha 从 Redis 取码）→ gateway `/api/home/feed`：**trace `f976dce7...770011` 含 29 spans，服务=[my-xhs-gateway, my-xhs-home]，refs 含 `CROSS_PROCESS` + `CROSS_THREAD`**（endpoints：`/api/home/feed`、`GET:/api/home/feed`、Lettuce/ZREVRANGE、Lettuce/SMEMBERS） |
| 跨线程链路 | home 的 Lettuce 异步调用 `CROSS_THREAD` ref 完整 |
| 当日段量（按服务） | gateway 66 / product 1078 / cart 1815 / home 327 / search 278（含健康检查与真实请求） |

## 四、边界与运维

- **采样**：9.x 默认 `sample_n_per_3_secs=1`（每 3 秒 1 条）；要全采样用 `SW_AGENT_SAMPLE=-1`（注意压测时开销）
- **开销**：每实例 agent 常驻（堆外），已随发布验证无健康问题；重启脚本/发布脚本均可关闭 agent
- **升级**：换 `SW_AGENT_DIR` 指向新版本目录即可；插件调整清单见本文第一节
- **UI**：SkyWalking UI 容器 `my-xhs-skywalking-ui`（宿主 8080 已被 OAP 占用时按容器实际映射访问）

## 五、面试口径更新

- xhs/51 题的"当前无 agent"边界 → 已改为"2026-09-18 已接入并验证"（29 spans 跨进程实证）
- 防御手册 V′ SkyWalking 行同步更新
