# F-003 order Feign 配置保留公开已知的 internal token fallback

## 严重度

High

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/feign/InternalCallFeignConfig.java:18`
- `my-xhs-common/src/main/java/com/myxhs/feign/config/FeignInternalCallInterceptor.java:24`
- `my-xhs-order/src/main/resources/application.yml:201`

## 现象

order 服务的专用 Feign 配置仍使用：

```java
@Value("${myxhs.internal.token:my-xhs-internal-token-2026}")
```

这与 common 全局拦截器、各服务 yml 的“缺失即空、fail-closed”策略不一致。

## 证据

- `my-xhs-order/.../InternalCallFeignConfig.java:18`：缺少配置时自动回退到公开可读字符串 `my-xhs-internal-token-2026`。
- `my-xhs-order/.../application.yml:201-206`：当前 yml 的 fallback 是空值，不能消除 Java 注解中的 fallback。
- `my-xhs-common/.../FeignInternalCallInterceptor.java:12-14`：common 明确要求缺失 token 时不携带 header、fail-closed。
- order 的 `InternalCallFeignConfig` 被 `ProductFeignClient`、`InventoryFeignClient`、`PaymentFeignClient`、`CouponFeignClient` 使用，见对应 FeignClient 的 `configuration = InternalCallFeignConfig.class`。

## 影响

1. order 进程如果未获得 `myxhs.internal.token`，仍可能向下游发送公开已知的内部令牌。
2. 任何能访问下游服务端口、且知道该公开值的调用方，都可能伪造内部服务调用。
3. 这会把 F-001/F-002 所依赖的直连暴露面进一步扩大为可利用的内部信任绕过。

## 修复建议

把 fallback 改为空字符串并拒绝启动或拒绝调用：

```java
@Value("${myxhs.internal.token:}")
```

同时统一删除各服务/Feign 配置中所有公开默认 token，并增加启动时的凭据完整性检查。

## 复核建议

检查所有 `@Value("${myxhs.internal.token:...}")` 与 `@Value("${myxhs.admin.token:...}")`，确保没有任何非空公开 fallback。