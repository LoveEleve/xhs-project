# 专项/演示开关速查（运维）

> 本平台多项能力为**默认关闭、按需开启**；重启或发布服务时需带对应参数。集中登记避免"能力悄悄消失"。
> 用法：`JVM_EXTRA="-D<flag>=true" bash scripts/release-service.sh <module>`（INSTANCE_ID 模式同样生效）。

| 能力 | 开关 | 默认 | 当前运行态 | 参考 |
|---|---|---|---|---|
| SkyWalking APM | `SW_AGENT_DIR` / `SW_AGENT_DISABLED=1` / `SW_AGENT_SAMPLE` / `SW_AGENT_IGNORE_SUFFIX` | 自动探测（agent 包存在即启用） | **15 服务全启用** | `skywalking-agent-enable-20260918.md` |
| 服务侧 zone 同区优先 | `myxhs.availability.zone.preference.enabled` | false | 关闭（基线） | `zone-*-20260918.md` |
| 网关 zone 同区优先 | `-Dmyxhs.availability.zone.preference.enabled=true` **且** `-Dmyxhs.current.availability.zone=<zone>` | false / defaultZone | 关闭（基线） | ⚠️ 缺后者会静默 `invalid_zone`（路由全部实例） |
| 动态 zone 数据源（content） | `myxhs.availability.zone.dynamic-datasource.enabled` | false | 关闭 | `dynamic-zone-jdbc-spring-20260918.md` |
| 映射表动态数据源（order） | `order.mapping.zone-routing.enabled` | false | 关闭（基线；2026-09-18 23:15 全量发布未带 flag） | `shardingsphere-dynamic-datasource-20260918.md` |
| 多 ORM（content JPA） | `content.jpa.enabled` | false | 关闭（基线；JPA 演示开关已关，基线启动依赖 yml 排除 `JpaRepositoriesAutoConfiguration`） | `multi-orm-coexistence-20260918.md` |
| 压测影子表 | `myxhs.shadow.enabled=true` + 请求头 `X-Pressure-Test` | false | 关闭 | xhs/22 题 |
| OTel（xhs-ai） | systemd drop-in：`JAVA_TOOL_OPTIONS=-javaagent:...otel-agent.jar` + `OTEL_*` 环境变量 | 已安装并启用 | **开启（OTel Agent 2.10.0）** | 模板 `deploy/ops/xhs-ai-otel.conf`；报告 `ai-otel-enable-20260918.md` |
| OAP OTel 接收 | `receiver-otel.enabledHandlers` 含 `otlp-traces` + `receiver-zipkin` 开启 | 已配置 | **开启（OTLP 复用 11800；zipkin 9411）** | `deploy/docker/my-xhs-deploy-zip/config/skywalking/application.yml` |
| 网关 HMAC 签名 | `gateway.auth.hmac-enabled` | false | 关闭 | xhs/17 题 |

## 重新开启两项 Demo（按需，当前均为关闭基线）
```bash
JVM_EXTRA="-Dorder.mapping.zone-routing.enabled=true" bash scripts/release-service.sh order
JVM_EXTRA="-Dcontent.jpa.enabled=true" bash scripts/release-service.sh content
# 关闭：不带 JVM_EXTRA 重新发布即可
```

## 全量发布注意
`scripts/release-service.sh <module>` 默认不带 `JVM_EXTRA`，批量发布 15 服务会把所有 Demo 开关打回基线；
批量发布后如仍需演示态，请按上表逐项重开。
