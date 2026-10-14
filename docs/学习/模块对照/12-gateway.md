# 12 · gateway 核心（my-xhs-gateway）· 梳理

> 系列：模块对照 · 阶段一（梳理）。**只记录，不讨论**（问题线索见 §7，待讨论）。
> 口径：标尺 = **真实 API 网关该有的样子**（见 §2）。按章程"只讲核心，权限非核心"——权限链只记不展开。
> 规模：17 类 / 2137 行。无测试。深度档：**支撑轻**。
> 建立：2026-10-12。

---

## 1. 定位与职责

**唯一入口**（WebFlux 响应式）：**8 过滤器链**（日志→鉴权→BodyCache→染色→HMAC→限流→灰度→版本）+ **16 条路由**（`lb://` 全服务覆盖）+ 同区优先（Zone）+ 统一异常语义。

不依赖 common 模块（WebFlux vs Servlet）——多处"独立实现 + 常量硬编码同步要求"的边界样本。

## 2. 应然基准（标尺）与实然对照

| 应然能力 | 我们 | 差距 |
|---|---|---|
| 统一鉴权 | ✅ JWT（白名单放行/黑名单吊销/type=access 校验/**header `set` 覆盖防伪造**【C-07】/sub 空拒绝【RV30】）；注入 X-User-Id/X-User-Role/X-Trace-Id | — |
| 限流 | ✅ Sentinel 路由级 QPS（16 路由逐条配值、注释带实测余量）；Nacos 规则源 + 30s 真空期兜底 + T-106 双适配器 | ⚠️ **用户级限流"后续实现"**（见 §7-2） |
| 请求签名（防篡改/重放） | ✅ HMAC-SHA256 完整设计：per-session secret、签名串=method\|path\|query\|ts\|nonce\|bodyHash、±5min + nonce SETNX、**先验签后占 nonce**【RV30】、常量时间比较 | ⚠️ **hmac-enabled=false（默认关闭，链路不生效）** |
| 灰度/版本路由 | ⚠️ 打标（userId hash M11 修符号位）/透传/属性就绪——**实例级过滤未实现**（"GrayLoadBalancer 需后续实现"） | 半成品（§7-1） |
| 可观测 | ✅ Trafik 染色 6 头全链透传 + trace 格式白名单防注入 + 鉴权/HMAC 失败指标按 reason + 入口日志（nanoTime） | — |
| 异常语义 | ✅ 401（未认证）/403（签名）/429（限流）/503（下游不可用、认证依赖故障）/504（下游超时【T-132】）区分 | — |

## 3. 数据模型

无库。Redis 键：

| 键 | 用途 |
|---|---|
| `myxhs:gateway:nonce:{nonce}`（5min） | HMAC 防重放（nonce 一次性） |
| `myxhs:user:hmac:secret:{userId}`（读） | per-session HMAC 密钥（user 域登录时写，gateway 验签读） |
| `myxhs:user:token:blacklist:{jti}`（读） | Token 吊销黑名单（**前缀硬编码**——Gateway 不能引 common，注释要求两侧同步） |

## 4. 核心链路

### A. 过滤器链（order 含设计意图）

```
+100  RequestLogFilter     入站/出站日志；traceId 格式白名单（32hex/安全字符，防日志注入）；nanoTime 计时
+1000 GatewayAuthFilter    JWT 全链校验 → 黑名单（Redis，RV33 移出 EventLoop）→ 注入身份（set 覆盖）
+1100 BodyCacheFilter      缓存请求体供 HMAC bodyHash（**从 HIGHEST 降到 1100**：不在鉴权前读未认证 body，缩小攻击面）
+1200 TrafficColoring      6 染色头：TraceId/GrayTag(默认stable)/ApiVersion(默认v1)/ABGroup(userId hash)/PressureTest；需 X-User-Id 故在鉴权后
+1500 HmacSignatureFilter  验签（RV34 阻塞 Redis 移出 EventLoop；secret 读失败 fail-closed 403；nonce 去重失败 fail-open）
+2500 RateLimitFilter      Sentinel 路由级 QPS（规则：Nacos JSON 源 → 30s 未到则 yml metadata 兜底；T-106 Gateway/WebFlux 双 BlockHandler）
+3000 GrayRouteFilter      X-Gray-Tag 或 userId hash<grayPercent(10) → "gray"（仅判定+日志+属性）
+3100 ApiVersionFilter     X-Api-Version 默认 v1；写 exchange attribute（供自定义 LB 读取）
```

### B. HMAC 验签（设计最完整的链路；当前配置未启用）

```
前置：BodyCacheFilter 已缓存 body → bodyHash=sha256(body)（multipart 按空串）
签名串 = method | path | query | X-Timestamp | X-Nonce | bodyHash
密钥：Redis per-session secret（登录时下发，前端保存）——弃用全局硬编码密钥的设计演进
校验：±5min → 取 secret（NULL=过期 403）→ HMAC 比对（MessageDigest.isEqual 常量时间）
     → 通过后才 SETNX nonce（5min）→ 放行
```

### C. 限流与规则治理

```
规则加载时机 = ApplicationReadyEvent（@PostConstruct 时 Nacos 数据源未就绪会误加载本地规则并覆盖后续推送）
若配置 Nacos 数据源（spring.cloud.sentinel.datasource.flow.nacos）→ 等推送；30s 真空期后仍空 → 本地兜底
本地兜底规则 = yml 路由 metadata.rate-limit-qps（缺省 100）
16 路由 QPS（注释带实测依据）：content 500 / product 500（"实测 ~1020 RPS，约50%余量"）/ search·home·recommend 300
     / user·cart·notification 50 / order 10 / payment 5 / inventory·coupon 30 / analytics·counter·im·ai 100
```

## 5. 对外契约

| 方向 | 内容 |
|---|---|
| 路由 | 16 条 `lb://`（user/content/search/order/payment/inventory/analytics/counter/product/cart/coupon/home/notification/im + **recommend-service→lb://my-xhs-search**（推荐在 search 模块内）+ xhs-ai） |
| 鉴权白名单 | ~81 条路径（公开读/登录/内部回调/管理端点"JWT+Admin 双层保护放行"等，另有 HMAC 独立白名单） |
| 数据源 | 无（Redis 只读用户域键 + nonce 自管） |

## 6. 机制清单

| 机制 | 说明 |
|---|---|
| Zone 同区优先 | `${MYXHS_ZONE:defaultZone}` + ZonePreferenceFilter + 自定义 LB（同区不足回退全部实例）——WebFlux 独立实现 |
| 指标对齐 | P1 修复：手动注入 `application=my-xhs-gateway` 公共标签（不引 common 则 MetricsAutoConfiguration 缺失） |
| 异常处理器 | ErrorWebExceptionHandler：连接拒绝/超时 503；T-132 识别 ResponseStatusException 直取 statusCode（504 语义） |

## 7. 现状边界与问题线索（仅记录，待讨论）

1. **灰度/版本路由半成品**：GrayRouteFilter"记录日志并注入属性"、ApiVersionFilter"供自定义 LoadBalancer 读取"——实际实例级过滤注释自认"**GrayLoadBalancer 需后续实现**"，即灰度标记打到但**没人在路由侧消费**（整链半通，死路径候选——与 IM 未接线 LB 同家族）；
2. **用户级限流失效位**：RateLimitFilter javadoc 明示"用户级限流（paramItem 从 X-User-Id 提取）**后续实现**"——当前仅**单机**路由级 QPS；爬虫按路由均摊即可绕过（呼应 home §7-5：网关对 /api/home 的兜底=300 QPS/路由，非按用户）；
3. **HMAC 默认关闭**（`hmac-enabled: false`）：链路代码完整但当前不生效——启用需前端配合"登录响应取 per-session secret"（前后端协议变更，属未启用的完整功能）；
4. **黑名单键硬编码同步**：`myxhs:user:token:blacklist:` 与 common 常量跨模块手工同步（WebFlux 无法复用 common 的代价）——修订风险点；
5. **三条 Redis 依赖三种失败策略**（好样本）：Auth 黑名单 → 503 fail-closed；HMAC secret → 403 fail-closed；HMAC nonce → fail-open——均带注释自证；但 **Redis 是认证单点**（注释自述"生产建议本地缓存+变更广播或多副本"）；
6. **T-106 兼容性脆点**：Sentinel 1.8.8（编译于 Spring 5）与 WebFlux 6.1 的 NoSuchMethodError → 限流时请求悬挂 30s——必须双 BlockHandler 注册（Gateway + WebFlux）才平稳；
7. 小项：白名单 ~81 条（清单治理观察）；40+ 行路由注释带实测 RPS 与余量（"性能基线文化"的正面样本）；BodyCache 抢在鉴权后读 body 的 order 修复（攻击面收敛）。
