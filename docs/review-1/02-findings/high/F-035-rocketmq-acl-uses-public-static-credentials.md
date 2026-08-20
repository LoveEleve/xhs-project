# F-035 RocketMQ ACL 使用公开静态 accessKey/secretKey

## 严重度

High

## 涉及文件

- `config/production-env-config/docker-container-review-20260811-162334/04-containers/my-xhs-mq-broker/config-files/plain_acl.yml:21-38`
- `config/production-env-config/docker-container-review-20260811-162334/04-containers/my-xhs-mq-namesrv/config-files/plain_acl.yml:21-38`
- 同目录 `tools.yml:17-18`

## 现象

RocketMQ ACL 配置中直接出现静态、低强度凭据：

- `accessKey: RocketMQ` / `secretKey: 12345678`
- `accessKey: rocketmq2` / `secretKey: 12345678`

这些值同时出现在 broker、namesrv 和工具配置中。

## 影响

1. 能访问 RocketMQ 端口的攻击者可伪造 producer/consumer 身份。
2. 可读取、发送、重放或篡改业务消息，影响订单、支付、库存、通知和 AI 数据链路。
3. MQ 凭据与 F-033 host 网络、F-029 防火墙不持久化、F-019 凭据提交叠加后，消息总线成为高价值攻击面。

## 修复建议

1. 立即轮换静态 ACL 凭据，按 producer/consumer/运维工具拆分最小权限账号。
2. 通过 secrets 注入，不在 compose/快照/仓库中保存明文。
3. 启用 RocketMQ 传输加密或至少限制 namesrv/broker 仅内网可达。
4. 验证所有业务服务的 ACL 配置与运行态一致。

## 是否需要补充验证

检查当前 broker 实际加载的 ACL 文件，并从非授权客户端验证是否能发布/消费业务 Topic。