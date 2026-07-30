# ES traceId 字段缺失 — 问题说明（给中间件团队）

> 验证日期：2026-07-30
> 状态：**已修复**

---

## 根因

微服务 `logback-spring.xml` 中 `LOGSTASH` appender 的 `LogstashEncoder` 缺少 `<includeMdcKeyName>traceId</includeMdcKeyName>`。

```xml
<!-- ❌ 修复前 -->
<appender name="LOGSTASH" class="...LogstashTcpSocketAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>

<!-- ✅ 修复后 -->
<appender name="LOGSTASH" class="...LogstashTcpSocketAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>spanId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
    </encoder>
</appender>
```

受影响：全部 15 个模块（im/home/search/gateway/user/content/analytics/counter/product/cart/inventory/coupon/order/payment/notification），已全部修复。

## 验证

```
traceId=caad133c4e614c6fb0ea2dabc996814b
ES 命中 1 条 ✅
traceId 字段一致 ✅
tags=None ✅
```
