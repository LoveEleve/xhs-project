# Nacos 配置外置"假生效"排查与修复（2026-09-18）

> 结论：15 个服务的 `spring.cloud.nacos.config.shared-configs` 全部为**死配置**（SCA 2023 新机制要求 `spring.config.import`），
> 服务实际一直使用本地 yml fallback；已改为真实 `optional:nacos:` 导入并全量发布验证。

## 一、发现过程

1. 发布/运维巡检时发现：网关引用 `my-xhs-common.yaml`、`my-xhs-gateway.yaml`，但 Nacos `my-xhs` 命名空间中不存在（404）；
2. 部署包 `config/nacos/` 有 3 个待导入文件（common/gateway/redis），从未导入；
3. 深挖：14 个服务有 `spring-cloud-starter-alibaba-nacos-config` 依赖、均配置了 `shared-configs`，
   但 **无 `spring-cloud-starter-bootstrap`、无 `spring.config.import`**，Spring Cloud Alibaba 版本为 **2023.0.1.2**（新式 config data 机制）。

## 二、根因

- Spring Cloud 2020+ / SCA 2021+ 起，bootstrap 上下文默认关闭，`spring.cloud.nacos.config.shared-configs`
  **不再生效**；必须使用 `spring.config.import: nacos:...`（或显式引入 bootstrap starter）。
- 因此：**所有服务一直靠本地 `application.yml`/`application-datasource.properties` 运行**；
  配置外置只是"写了没生效"（连带 `import-check` 也未启用）。
- 网关更彻底：pom 里连 `nacos-config` 依赖都没有。

## 三、修复（真实生效）

1. 15 服务 `application.yml` 增加：
   ```yaml
   spring:
     config:
       import:
         - optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true
         # gateway 额外: optional:nacos:my-xhs-gateway.yaml?...
   ```
   `optional:` 保证 Nacos 不可用时**不影响启动**（本地 fallback 仍在，回滚零风险）；
2. 14 服务补 `spring.cloud.nacos.config.namespace: my-xhs`（此前只有 discovery 有，config 默认 public
   → 首次接线后表现为 `config[...] is empty`，请求日志 code=300）；
3. 网关 pom 补 `spring-cloud-starter-alibaba-nacos-config` 依赖；
4. 移除全部死配置块（`import-check`/`shared-configs`），避免误导；
5. 新增 `scripts/nacos-import-configs.sh`（幂等导入 3 个配置文件）；
6. 15 服务全量重建 + 发布（release-service.sh，含 PID 校验/回滚保护）。

## 四、验证证据

| 验证项 | 证据 |
|---|---|
| 修复前（public 查空） | `15:33:38 get my-xhs-common.yaml tenant=空 code=300`；应用日志 `config[...] is empty` |
| 修复后（正确命名空间） | `15:34-15:40 连续 15 次 get tenant=my-xhs code=200 md5=83d6035a...`（Nacos config-client-request.log） |
| 应用侧加载 | 15/15 服务日志出现 `[Nacos Config] Load config[dataId=my-xhs-common.yaml, group=DEFAULT_GROUP] success` + `Listening config` |
| 网关专属配置 | `15:35:06 get my-xhs-gateway.yaml code=200 md5=122ef3f8...` |
| 健康 | 15/15 UP；发布脚本 verified_health（监听 PID==启动 PID）全部通过 |

## 五、连带发现（部署包不一致）

- `sql/04-nacos-config-seed.sql` 为 2026-08 历史快照，其中网关键结构过期
  （`gateway.jwt.secret` ≠ 现在的 `gateway.auth.secret`）、search 数据源仍指向旧端口 13307；
  已在该 SQL 与 DEPLOY-NOTES 中标注"不要用种子替代文件导入"，避免新环境踩坑；
- `config/nacos/my-xhs-gateway.yaml` 与 `my-xhs-common.yaml` 值均与本地 fallback 一致，
  因此本次切换**零行为变化**（纯机制生效）。

## 六、安全与边界（主动披露）

- 密码/密钥仍为明文（P-D1 已知债），本次只是让 Nacos 外置**真实生效**；
  后续可接 Nacos 鉴权 + 环境变量注入；
- 未做运行时热更新演练（`refreshEnabled=true` 已开启，可另行验证）。

## 七、面试价值

- 真实故事：**"配置写了但没生效"** 是配置中心接入的经典深坑（版本机制变更）；
- 排查链路完整：404 → 依赖/机制检查 → 首次接线暴露命名空间缺失 → code=300 → 修复 → 200；
- 工程取舍：`optional:` 保启动韧性；全量发布用带指纹校验的发布脚本闭环。
