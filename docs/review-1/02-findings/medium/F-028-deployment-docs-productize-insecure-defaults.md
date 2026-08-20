# F-028 部署文档把不安全默认值产品化，容易把临时环境习惯带入长期运行态

## 严重度

Medium

## 涉及文件

- `config/deploy-cloud/DEPLOY-README.md:70-74`
- `my-xhs-common/src/main/java/com/myxhs/common/config/RedissonConfig.java:55-67`
- `config/nacos/*`

## 现象

部署文档和代码示例中反复出现“不安全但可运行”的默认值与做法，例如：

- Nacos 默认 `nacos/nacos` + 无鉴权
- 中间件密码使用统一前缀 `Xhs@2026#*`
- Sentinel Dashboard 默认口令
- Redisson 示例配置内含公开 Redis 地址和密码

这些值虽然有时被标注为“待办/建议后续改”，但它们已经被写成了可直接复制的部署说明。

## 证据

1. `DEPLOY-README.md:70-74` 直接列出 Nacos 无鉴权、中间件明文密码、Sentinel 默认口令。
2. `RedissonConfig.java:55-67` 的注释示例包含公开 Redis 地址和密码。
3. `DEPLOY-README.md:93-94` 还鼓励通过 curl 导入 Nacos 配置，若配合 F-026 的未鉴权运行态，风险更高。

## 影响

1. 团队会把“临时可跑通配置”误当成默认生产姿势，安全 debt 被制度化。
2. 新环境最容易直接复制这些值，导致跨环境凭据复用。
3. 文档本身会成为敏感信息与不安全操作的扩散源。

## 修复建议

1. 部署文档只保留安全占位符，不展示真实地址/口令模式。
2. 对所有“必须改”的默认值使用阻断式文案，而不是建议式文案。
3. 为 Nacos/Redis/JWT/Sentinel/XXL-Job 建立一份统一的“上线前必须替换项”检查清单。
4. 代码注释中的配置示例也应使用占位值而非真实样式密码。

## 是否需要补充验证

不需要额外验证；文档与示例本身已构成证据。