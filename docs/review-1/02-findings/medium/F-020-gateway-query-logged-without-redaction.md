# F-020 Gateway 请求日志完整落 query string，敏感参数会直接入日志

## 严重度

Medium

## 涉及文件

- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/RequestLogFilter.java:47-75`

## 现象

Gateway 入站日志会在 query string 非空时完整记录：

```java
log.info("[Gateway] >>> method={}, path={}, query={}, traceId={}", method, path, query, traceId);
```

这意味着只要某个接口把 ticket、临时凭据、调试参数或未来新增的敏感参数放进 query，都会直接落入日志。

## 证据

1. `RequestLogFilter.java:47` 读取 `request.getURI().getRawQuery()`。
2. `RequestLogFilter.java:70-75` 在 query 非空时直接原样记录。
3. 代码注释只声明“不记录请求体/响应体”，没有对 query 做脱敏或白名单。

## 触发条件

1. 任意接口使用 query 参数承载敏感值（ticket、签名、管理参数、调试口令等）。
2. 或未来新增接口误把凭据放在 query 上。

## 影响

1. 凭据会进入网关日志、日志采集链路和可观测平台。
2. 即使业务接口本身已修正不再用 query 传 refreshToken，新增接口仍可重犯。
3. query 泄漏比 body 泄漏更隐蔽，因为很多人默认日志只关注 body。

## 修复建议

1. Gateway 对 query 做统一脱敏或默认不记录 query 值，只记录参数名。
2. 对确需记录 query 的接口建立白名单，不允许全量直出。
3. 把 ticket、token、签名、secret 等敏感参数模式纳入日志脱敏规则。
4. 对现有日志链路做一次回扫，确认没有历史 query 泄漏。

## 是否需要补充验证

需要抽样检查网关日志与 Logstash/ELK 中是否已经落过 ticket、签名或其他敏感 query 参数。