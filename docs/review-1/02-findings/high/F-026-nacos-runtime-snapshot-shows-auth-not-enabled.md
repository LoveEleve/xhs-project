# F-026 Nacos 运行快照显示鉴权未开启，配置中心存在未授权读写风险

## 严重度

High

## 证据来源

- 仓库内运行快照：`config/production-env-config/docker-container-review-20260811-162334/04-containers/my-xhs-nacos/config-files/application.properties`
- 同目录 `announcement_zh-CN.conf` / `announcement_en-US.conf`

## 现象

从仓库保留的运行快照看，Nacos 配置中心在该环境下很可能未开启完整鉴权：

1. `nacos.core.auth.plugin.nacos.token.secret.key=${NACOS_AUTH_TOKEN:}` 为空环境变量占位。
2. `nacos.core.auth.server.identity.key/value` 也为空环境变量占位。
3. 快照公告文件直接写明“当前集群没有开启鉴权”。

## 证据

1. `application.properties:34`：`nacos.core.auth.plugin.nacos.token.secret.key=${NACOS_AUTH_TOKEN:}`。
2. `application.properties:38-39`：`nacos.core.auth.server.identity.key/value` 为空占位。
3. `announcement_zh-CN.conf:1`：`当前集群没有开启鉴权...`。
4. `announcement_en-US.conf:1`：`Authentication has not been enabled in cluster...`。

## 影响

1. 若该快照反映真实运行态，任何能访问 Nacos 的人都可能读取或修改服务配置。
2. 配置中心中承载 Redis、JWT、内部 token、数据源等关键配置，一旦被改写，影响面覆盖全部微服务。
3. 即使应用侧实现正确，配置中心失守也会让所有服务的信任边界同时失效。

## 修复建议

1. 立即核对当前 Nacos 真实运行态，确认 auth 是否开启，而不是只看仓库默认配置。
2. 强制配置 `NACOS_AUTH_TOKEN` 与 `NACOS_AUTH_IDENTITY_KEY/VALUE`，并验证服务端拒绝未授权请求。
3. 对 Nacos 暴露面做网络收口，只允许受控来源访问。
4. 清点配置中心中存放的敏感配置，评估是否已暴露并需要轮换。

## 是否需要补充验证

需要从当前运行环境实际访问 Nacos API/控制台，验证未授权访问是否被拒绝；仅凭仓库快照不能代表今天的运行态，但已足够说明存在高风险历史/环境漂移。