# AI 项目 OTel 接入（2026-09-18）

> 缺口背景：ai/18 登记"OTel/Langfuse 0 命中"——框架级观测未落地。
> 本次用 **OTel Java Agent → SkyWalking OAP OTel 接收器** 路线打通，无需自建 collector/Langfuse。

## 一、实现

1. **OAP 开启 OTel traces 接收**（`deploy/docker/my-xhs-deploy-zip/config/skywalking/application.yml`）：
   - `receiver-otel.enabledHandlers` 增加 `otlp-traces`（原仅 metrics/logs）
   - `receiver-zipkin.selector` 由禁用改为 `default`——OTel traces 在 OAP 内**转发给 Zipkin 接收模块**存储（此前导出报 `ModuleNotFoundRuntimeException: receiver-zipkin missing`，gRPC status 2）
   - 重启 OAP：`OpenTelemetryTraceHandler` 绑定 11800（OTLP/gRPC 与 SW 原生协议共端口）；zipkin 接收器监听 9411
2. **xhs-ai 挂 OTel Agent**（systemd drop-in，不改原 unit）：
   - `JAVA_TOOL_OPTIONS=-javaagent:/data/workspace/otel-agent/opentelemetry-javaagent.jar`（2.10.0，21MB，仓库外；安装脚本 `scripts/install-otel-agent.sh` 幂等复现）
   - `OTEL_SERVICE_NAME=xhs-ai-otel`、`OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:11800`（grpc）、traces=otlp、metrics/logs=none
   - 路径：`/etc/systemd/system/xhs-ai.service.d/otel.conf`（模板已入库：`deploy/ops/xhs-ai-otel.conf`）

## 二、验证

| 项 | 结果 |
|---|---|
| Agent 加载 | journal：`Picked up JAVA_TOOL_OPTIONS: -javaagent:...` + VersionLogger |
| 导出（修复前） | `Failed to export spans. gRPC status 2`（receiver-zipkin missing） |
| 导出（修复后） | **20:58 后失败数 0** |
| 存储落地 | `sw_zipkin_span-20260918` = **52 条 span** |
| Span 样本 | `mcphealthmonitor.checkandheal`、`approvalexpiryjob.expirepending`（AI 内部组件，自动埋点生效） |
| 服务健康 | xhs-ai 200（agent 挂载后无异常） |

## 三、边界（主动披露）

- SkyWalking UI 原生视图主要面向 SW 原生探针数据；OTel 数据落在 `sw_zipkin_span`（可按 Zipkin 格式/ES 查询），**UI 集成视图有限**；
- 仅启用 traces（metrics/logs 关闭）；采样为默认（parentbased_always_on），高流量需评估；
- Langfuse（LLM 语料级观测）仍未部署——**与 OTel 不冲突**，OTel 已覆盖运行时链路。
