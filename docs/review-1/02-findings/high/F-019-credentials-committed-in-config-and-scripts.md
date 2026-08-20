# F-019 生产凭据与基础设施密码直接提交在配置和测试脚本中

## 严重度

High

## 涉及范围

- 多个 `my-xhs-*/src/main/resources/application.yml`
- `my-xhs-order/src/main/resources/sharding-config.yaml`
- `full-chain-test*.sh`
- `my-xhs-common/src/main/java/com/myxhs/common/config/*RedisConfig.java`

## 现象

仓库中直接出现 Redis、MySQL、Elasticsearch、JWT、HMAC 等生产或准生产凭据，例如：

- Redis：`Xhs@2026#Redis`
- MySQL：`Xhs@2026#MySQL`
- Elasticsearch：`Xhs@2026#Elastic`
- JWT：`MyXhs@2026#JwtSecretKey!ForTokenSign`
- Gateway HMAC：`myxhs-hmac-secret-key-2024`

这些值不仅存在 application 配置，也出现在全链路测试脚本和 shared common fallback 中。

## 证据

- 各服务 `application.yml` 中 Redis password/JWT secret 明文配置。
- `my-xhs-order/src/main/resources/sharding-config.yaml:19` 等处直接写 MySQL password。
- `my-xhs-search/src/main/resources/application.yml:97` 直接写 ES password。
- `my-xhs-gateway/src/main/resources/application.yml:286-289` 直接写 JWT/HMAC secret。
- `full-chain-test-v2.sh`、`full-chain-test-v3.sh` 直接写 Redis password。
- common Redis 配置还提供同类公开 fallback，见 F-018。

## 影响

1. 仓库读取权限等同于基础设施认证材料泄漏。
2. JWT/HMAC secret 泄漏后可伪造身份、签发 token 或构造签名。
3. Redis/MySQL/ES 凭据泄漏后可能直接读取会话、业务数据或修改状态。
4. 测试脚本和文档容易被复制到其他环境，造成跨环境凭据复用。

## 修复建议

1. 所有敏感值改为密钥管理系统、环境变量或 Nacos 加密配置，仓库只保留占位符。
2. 立即轮换已提交过的 Redis/MySQL/ES/JWT/HMAC 凭据。
3. 对 Git 历史进行 secret scan，已提交凭据不能只靠当前文件删除来视为安全。
4. 测试脚本使用运行时注入，不内置真实密码。
5. 启动时拒绝已知默认值和空的生产敏感配置。

## 是否需要补充验证

需要对整个 Git 历史运行 secret scan，并核对当前运行环境是否仍在使用这些值。