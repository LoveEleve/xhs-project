# 第17题 | 网关过滤器顺序与 HMAC 防重放

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：过滤器链顺序、BodyCache、JWT、HMAC-SHA256、per-session secret、nonce SETNX、时间窗、fail-open

## 问题
问题：网关的过滤器怎么排序？HMAC 签名怎么防篡改防重放？为什么生产默认是关闭的？

## 面试可讲版（五段式）

**① 业界背景**
网关过滤器链的核心是**顺序即安全模型**：body 缓存必须最先（后面要读），日志要在最前（记录原始请求），鉴权在业务前，限流尽量靠前（省资源）。HMAC 签名是"防篡改+防重放"的经典方案（AWS SigV4 同思路）：签名输入要包含方法/路径/时间戳/随机数，配合**时间窗 + nonce 去重**；密钥管理是最大坑（全局密钥=泄露即失效）。

**② 项目选择**
- **过滤器顺序**（以 `getOrder()` 实测为准）：`BodyCache(+1100)` → `RequestLog(+100)` → `Auth(+1000，JWT)` → `HMAC(+1500)` → `RateLimit(+2500)`（C-015 口径：Gray/ApiVersion 两过滤器评估后移除——属性无消费者、实例级 LB 未实现，与 im 死 LB 同族）；
- HMAC 规则（**以代码为准**）：`sign = HmacSHA256(perUserSecret, method|path|query|timestamp|nonce|bodyHash)`——**含 query 与 body 摘要**（`bodyHash=sha256Hex(body)`，multipart 按空 hash）；三个 Header：`X-Timestamp`/`X-Nonce`/`X-Signature`；注意类顶部注释还是旧版四项（method+path+ts+nonce），代码 T-009/010/011 已扩展——又一处注释漂移；
- 防重放：时间戳容忍 **5 分钟**；nonce 用 **Redis SETNX + TTL 5min**（Lua 保证原子），多实例共享去重状态；
- **密钥不用全局硬编码**：改为**登录时生成 per-session secret 存 Redis**（`myxhs:user:hmac:secret:{userId}`，前端从登录响应取，网关从 Redis 取验签）——代码注释原话"配置文件硬编码，前端知道=签名失效"；
- 白名单路径跳过（注册/登录）；**默认关闭**（`hmac-enabled: false`）；Redis 异常时**放行**（签名是安全增强，不是核心鉴权）。

**③ 坑**
- **全局密钥硬编码**：放配置文件里的密钥等于公开（前端/测试客户端都能拿到）→ 改 per-session secret，网关反查 Redis；
- **nonce 占用时机**（RV31 修复）：必须**验签通过后再占 nonce**——先占后验会让无效签名也能消耗 nonce 窗口/干扰重放判定；
- **body 读一次就没了**：所以 BodyCacheFilter 必须最先执行并缓存 body（multipart 特殊处理按空 bodyHash 口径）；
- **注释漂移×2**：① 过滤器注释写"在 +1000 之前"，实际 `getOrder()=+1500`（+1000 是 Auth）；② 签名规则注释是旧四项、实现已是六段（含 query/bodyHash）——答辩以代码为准，这类"注释与实现不一致"正是 review 要抓的；
- 默认关闭是**兼容与测试口径**（本地/压测/HMAC 未启用时全链路不带签名），别把它讲成"已经全量防护"。

**④ 兜底**
- Redis 挂 → 放行（宁可漏放不误拒），JWT 仍是硬鉴权；
- nonce TTL 自动清理，不会无限增长；时间窗拒绝过期请求，防止"重放一个月前的有效包"；
- 统一 401/429 JSON（与 AuthFilter 格式一致，前端可识别）；
- 实测：Gateway + HMAC 重测 07-inventory **11/11** 通过（FINAL-HANDOFF）。

**⑤ 话术**
> "过滤器链条就是安全模型：body 缓存（HMAC 开启时才缓冲）、JWT 鉴权、HMAC 签名、再限流。HMAC 的密钥我们踩过全局硬编码的坑，改成登录时发 per-session secret、网关从 Redis 取；nonce 是验签通过才占，时间窗 5 分钟。签名默认关闭是兼容口径；JWT 密钥启动 fail-closed（空/演示密钥拒绝启动，与 im 一致）。"

## 追问与参考回答
**追问1：为什么 HMAC 与 JWT 都要？** JWT 解决"你是谁/能不能进"（认证），HMAC 解决"这段请求有没有被改/有没有被重放"（完整性）；互补关系。
**追问2：防重放和幂等什么区别？** 防重放是安全层（拒绝攻击者的重放包），幂等是业务层（重复包也无害）；两者都要有，不能互为替代。
**追问3：per-session secret 怎么轮换/失效？** 登录生成、登出/踢出时删 Redis 键；旧 secret 随会话过期失效；比全局密钥差在"每请求多一次 Redis 读"——可接受（本来就有鉴权查 Redis）。
**追问4：时间戳用客户端时间会不会有问题？** 会有——客户端时钟偏差超过窗口会被误拒；所以窗口给 5 分钟容错，服务端以自身时间为准校验差值。
**追问5：为什么 nonce 用 SETNX 而不是查完再写？** 查+写非原子，并发下两个相同 nonce 都能通过；SETNX（或 Lua）保证只有一个赢家。

## 发散追问地图（横向）
- 签名体系：HMAC vs RSA/ECDSA、AWS SigV4、OAuth2 mTLS、API 网关的 key/secret 管理。
- 重放防护：nonce 存储选型（Redis/Bloom/本地缓存）、时钟同步（NTP）、窗口设计。
- 过滤器链：Spring Cloud Gateway GlobalFilter 排序、body 读取的坑（DataBuffer 释放）、性能（缓存 body 的内存成本）。
- 安全纵深：WAF、IP 黑名单（本项目有 gateway 黑名单）、CSRF、限流与签名顺序。
- 测试：签名客户端实现（脚本 sign(secret, method, path, ts, nonce)）、11/11 重测口径。

## 面试官评分点
**高级开发级**：能给出过滤器顺序与理由；能说清 HMAC 三要素（签名输入/时间窗/nonce）。
**架构师加分**：密钥体系（per-session 演进）、nonce 原子性、验签时机、默认关闭的边界表达、fail-open 的取舍。
**危险信号**：顺序拍脑袋；全局密钥当安全；nonce 非原子；把"默认关闭"说成已全量防护。

## 本项目真实证据
- 过滤器 `getOrder()`：`BodyCacheFilter`(+1100，C-015：HMAC 关闭时短路)、`RequestLogFilter`(+100)、`GatewayAuthFilter`(+1000)、`HmacSignatureFilter`(+1500)、`RateLimitFilter`(+2500)；Gray/ApiVersion 已移除（C-015）。
- `HmacSignatureFilter:30-67,150-166`：签名规则/三 Header/TTL 5min/SETNX+TTL/白名单/Redis 异常放行；per-session secret 注释与 Redis 键 `myxhs:user:hmac:secret:{userId}`。
- `BodyCacheFilter`：body 缓存先于 HMAC、multipart 口径、HMAC 关闭短路（C-015）；`application.yml`（`hmac-enabled: false`）；hmac-secret 全局密钥配置已随 per-session 设计移除（C-015 死配置）。
- 实测：FINAL-HANDOFF "Gateway + HMAC 重测 07-inventory 11/11"；RV31 修复（验签后占 nonce）。

## 版本与来源
Spring Cloud Gateway 过滤器文档；HMAC 防重放公开实践（AWS SigV4 思路）；本项目 gateway 代码与 RV31/FINAL-HANDOFF 记录。

## 真实性说明
顺序、签名规则、nonce 机制、per-session secret、默认关闭均为代码事实；"11/11"为测试记录；fail-open 是明确取舍。

## 本轮补充（2026-09-20 信任链与脱敏实测）
- **F-001 真事故**：信任过滤（剥离外部伪造直连 JWT）因双 `StringRedisTemplate` 注入歧义（`NoUniqueBeanDefinitionException` 被吞）**全线失效** → `@Qualifier` 修复 + 5 场景验证（覆盖生效/吊销拒绝 400+审计/内部调用/网关流量）。
- Redis 故障语义：401「Token 已被注销」→**503「认证服务暂不可用」**（fail-closed 可重试，不再误登出）。
- 日志脱敏：`RequestLogFilter.maskSensitiveQuery`（ticket 等敏感 query 打码）+ Logstash 管道漂移修复（挂载仓库 conf + gsub 脱敏）。
- 限流匹配度审计：16 路由 : 16 规则全覆盖、零误伤；两风险登记（支付回调 5 QPS / counter:get 50）。
- 残余风险：服务端口直连暴露面（网络层收敛项，登记）。
