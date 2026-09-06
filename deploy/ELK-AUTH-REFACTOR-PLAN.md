# ELK 与鉴权体系重构规划

> 分支: `refactor/elk-auth-simplify`
> 日期: 2026-08-24
> 目标: 在不牺牲核心业务边界的前提下, 对当前“日志链路 + 鉴权体系”做一次**更像生产、但更适合开发/测试/调试**的系统级收敛。
> 范围: 先做深度规划, 明确改造路线、保留点、删除点、兼容策略与实施顺序。

---

## 一、为什么要一起改 ELK 和鉴权

这两个问题表面无关, 但本质都指向同一个症状:

- **系统边界太碎**
- **运行链路太绕**
- **开发/测试成本太高**
- **文档和现实之间容易漂移**

### 当前的两大痛点

#### 1. 日志链路处于“过渡态”
- 应用既落盘 JSON, 又直推 Logstash TCP
- Logstash 同时收 TCP 与 beats
- 文档里有时说是完整 Filebeat ELK, 有时又说 filebeat 已移除

#### 2. 鉴权体系过于复杂
- 外部用户流量: `Authorization: Bearer ...`
- 写接口: 再叠 `HMAC`
- 管理接口: 再叠 `X-Admin-Call`
- 内部服务调用: 再叠 `X-Internal-Call`
- 服务端口上又有 `GatewayAuthTrustFilter` 去“覆盖/剥离/信任” `X-User-Id`

这导致:

1. 测试脚本极度繁琐
2. 调试一个接口常常要凑齐多层 header
3. 业务控制器里到处写 header 校验
4. AI/自动化工具接入也麻烦
5. 文档口径复杂, 难以长期维护

所以这次重构的目标不是“偷懒去掉安全”, 而是:

- **把边界收口**
- **把默认路径简化**
- **把例外场景显式化**

---

## 二、当前鉴权体系真实结构

## 2.1 当前外部流量链路

### gateway 侧
1. 公开端点: 无 JWT
2. 认证端点: JWT
3. 写操作: JWT + HMAC
4. 管理操作: JWT + `X-Admin-Call`

### 代码位置
- HMAC: `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/HmacSignatureFilter.java`
- 网关日志与 trace 辅助: `RequestLogFilter`
- Nacos 中还有独立 `gateway.hmac.secret`

## 2.2 当前服务端口直连保护

### common 侧 `GatewayAuthTrustFilter`
`my-xhs-common/src/main/java/com/myxhs/common/web/GatewayAuthTrustFilter.java`

它的行为是:

1. 若 `X-Internal-Call == internalToken`, 直接信任
2. 否则若有 Bearer JWT, 用 JWT subject 覆盖 `X-User-Id`
3. 否则剥离 `X-User-Id`

本质上是:

- 给“服务端口直连”加了一层补丁式信任边界
- 避免别人随便伪造 `X-User-Id`

## 2.3 当前内部服务调用

### Feign 侧
`my-xhs-common/src/main/java/com/myxhs/feign/config/FeignInternalCallInterceptor.java`

- 所有 Feign 自动注入 `X-Internal-Call`
- token 来自环境变量 `INTERNAL_TOKEN`

### controller 侧
很多内部或管理接口会自己验:

- `X-Internal-Call`
- `X-Admin-Call`
- 有时再配合 `X-User-Id`

例如:
- `UserController`
- `PaymentController`
- `InventoryController`
- `CounterController`
- `SearchController`

## 2.4 当前问题本质

当前不是“完全没安全”, 而是**安全边界分层太多**:

1. gateway 一层
2. HMAC 一层
3. admin header 一层
4. internal header 一层
5. service port 直连补丁层一层

结果是:

- 对开发调试极其不友好
- 对自动测试极其不友好
- 对 AI 工具链极其不友好
- 但并没有形成真正优雅统一的权限模型

---

## 三、重构目标

这次不建议简单“全部删掉”, 而是按目标重新定义原则。

## 3.1 新原则

### 原则 A: 外部用户认证只保留一套主路径
- **主路径 = JWT**
- 不再让普通用户写操作默认依赖 HMAC

### 原则 B: 内部服务调用只保留一套主路径
- **主路径 = Internal Service Token**
- 不再让每个服务各写一套乱七八糟的 header 组合

### 原则 C: 管理操作要么走角色, 要么走单独管理 token
- 但要集中定义, 不在各 controller 自己散写

### 原则 D: 开发/测试环境默认“可调试”
- curl、Postman、脚本、AI 工具不需要再拼 3~4 层 header

### 原则 E: 生产增强能力可选开启
- 真要保留 HMAC, 应做成**可开关的增强层**, 而不是默认主路径

---

## 四、建议的目标鉴权模型

## 4.1 外部用户流量

### 建议保留
- `Authorization: Bearer <access_token>`

### 建议取消默认依赖
- `HMAC` 作为所有写接口默认必需

### 原因
1. JWT 已经足够表达用户身份
2. HMAC 极大增加测试和开发成本
3. 你当前不是开放公网 API 平台, 而是内控系统/演示系统
4. 真正的“防篡改”价值在当前环境里收益不如成本高

### 新建议
- **生产默认: JWT 即可**
- **HMAC 改成可选增强**:
  - 仅对极少数高敏操作可开启
  - 或只在面向开放生态/第三方接入时启用

## 4.2 内部服务调用

### 建议保留
- `X-Internal-Call`

### 但要做的收敛
1. 统一成一个过滤器/拦截器处理
2. controller 层减少重复手工判断
3. 能抽成注解或统一鉴权器就不要每个接口手抄 if

### 原因
- 内部服务身份仍然需要
- 但现在实现太散

## 4.3 管理接口

### 建议保留一种就够
两种选择:

#### 方案 1: JWT + 管理角色
- 最像标准生产
- 需要更完整的 RBAC/权限模型

#### 方案 2: `X-Admin-Call` 单独保留
- 实现简单
- 对自动化/脚本友好
- 可作为“运维/后台调用令牌”

### 推荐
**短中期推荐方案 2**:
- 保留 `X-Admin-Call`
- 但统一抽象, 不要散在 controller 中手写

原因:
- 你现在更看重可用与效率
- 不是要做完整 IAM 平台

## 4.4 `GatewayAuthTrustFilter`

### 当前存在的原因
- 保护服务端口直连时伪造 `X-User-Id`

### 重构建议
保留它的核心职责, 但简化语义:

1. 内部调用 -> 信任 `X-Internal-Call`
2. 外部用户 -> 只信 JWT, 自动注入/覆盖 `X-User-Id`
3. 其他情况 -> 剥离伪造头

也就是说:

- **保留 filter, 但让它成为“统一入口保护器”**
- 不再配合一堆额外 HMAC 逻辑做复杂组合

---

## 五、建议删除/降级的内容

## 5.1 建议降级为可选项

### HMAC-Signature
当前建议:
- 不作为默认强制主链路
- 降级为“可选增强”

### 影响
- 需要修改 gateway 过滤逻辑
- 需要修改测试文档
- 会明显降低联调复杂度

## 5.2 建议从 root 默认链路移出的复杂 header 组合

- 普通用户写接口: 不再要求 `JWT + HMAC`
- 管理接口: 不再要求复杂叠加, 收敛为 `JWT + role` 或 `X-Admin-Call`
- 内部接口: 收敛为 `X-Internal-Call`

---

## 六、标准 ELK 硬切目标

## 6.1 当前日志主链路

```text
微服务
  -> /logs/my-xhs-*.json
  -> Filebeat
  -> Logstash(beats 15045)
  -> Elasticsearch
  -> Kibana
```

## 6.2 已移除的旧主链路

已移除的默认主链路:
- `LogstashTcpSocketAppender -> 15044`

当前结论:
- **应用默认不再直推 Logstash TCP**
- **只写本地 JSON 文件**
- **标准 ELK 完整接管日志采集**
- `15044` 即使在 Logstash 侧暂时仍监听, 也不再作为默认生产路径或默认文档口径。

---

## 七、ELK 硬切的改造项

## 7.1 微服务侧

### 需要改
1. 统一所有 `logback-spring.xml`
2. 保留 `JSON_FILE`
3. 从 root 里移除 `LOGSTASH` appender
4. 统一 `/logs/${APP_NAME}.json` 落盘策略
5. 审核 `maxHistory` / `totalSizeCap`

### 不需要改
- 业务代码
- traceId MDC 字段逻辑

## 7.2 部署侧

### 需要加
1. `filebeat` 服务加入 compose
2. `config/filebeat/filebeat.yml` 改为采 `/logs/*.json`
3. `filebeat` 挂载 `/logs:/logs:ro`
4. `filebeat` 输出到 `logstash:15045`

### 需要调
1. `logstash` 保留 beats 输入
2. 15044 TCP 输入可在过渡期保留, 但最终应弱化/去掉
3. Kibana 保持当前修复后的可用状态

## 7.3 文档侧

需要统一所有“口径冲突”:

- 不能一会儿说 Filebeat 已部署
- 一会儿又说 filebeat 已移除
- 要明确新默认: **标准 ELK = Filebeat 主链路**

---

## 八、当前实施顺序

## Phase 1: 标准 ELK 主链路改造（已完成配置改造）
1. `filebeat` 已加入 compose
2. `filebeat.yml` 已改为采 `/logs/*.json`
3. 微服务 root 主路径中的 `LOGSTASH` 已移除
4. 仍需完成 `/logs/*.json -> beats -> ES -> Kibana` 运行态验证

## Phase 2: 鉴权第一刀（已完成）
1. gateway 去掉默认强制 HMAC 主路径
2. HMAC 改为配置开关, 默认关闭
3. JWT 作为外部用户唯一主路径

## Phase 3: 鉴权第二刀（已完成主体）
1. 收敛管理与内部鉴权
2. controller 侧重复校验去重
3. 已完成主要代表服务的 `AccessTokenGuard` 收口

## Phase 4: 收尾（进行中）
1. 文档统一
2. 测试口径统一
3. 再决定是否补齐剩余少量 controller 收口点

---

## 九、再次复核后的优先级判断

### 9.1 面试价值判断

从当前项目定位看:

1. **更像面试亮点的**:
   - 微服务拆分与交易链路
   - MySQL / Redis / RocketMQ / ES 的真实业务链路
   - 可观测性(ELK / Prometheus / SkyWalking)
   - 补偿、幂等、异步一致性
2. **不是当前第一面试重点的**:
   - Nacos 鉴权
   - Alertmanager 通知出口
   - 复杂 HMAC 安全体系本身

也就是说:

- **鉴权不是当前最值得继续做加法的面试点**;
- 但它已经对开发、测试、调试造成了明显阻碍, 所以值得做“减法式收口”。

### 9.2 最优策略

因此更合理的顺序不是“先把安全体系做重”, 而是:

1. **先做标准 ELK 硬切**
2. **再做鉴权简化/收口**
3. **不做复杂安全能力扩张**

### 9.3 为什么 ELK 仍然优先

1. ELK 更贴近生产标准表达
2. ELK 对面试展示更直观
3. 改造主要集中在配置、部署、logback, 风险可控
4. 不会直接改变业务接口语义

### 9.4 为什么鉴权只做“收口”

1. 当前问题不是“太弱”, 而是“太烦”
2. 真正该做的是减少 header 组合与重复校验
3. 不建议把它升级成更复杂的 IAM / RBAC 大工程
4. 目标应该是:
   - 外部用户: **JWT 主路径**
   - 内部服务: **X-Internal-Call 主路径**
   - 管理接口: **X-Admin-Call 保留但统一抽象**
   - HMAC: **从默认必需降级为可选增强**

---

## 十、最终结论

### 关于 ELK
- **建议彻底切到标准 ELK**
- 应用默认不再直推 Logstash TCP
- Filebeat 成为唯一主链路
- 这是当前最值得先落地的大改造项

### 关于鉴权
- **建议重构, 但方向是“简化/收口”, 不是“继续强化/继续加层”**
- 最终收敛为:
  - 外部用户: JWT
  - 内部服务: X-Internal-Call
  - 管理接口: X-Admin-Call
  - HMAC: 从默认主路径降级为可选增强

### 推荐顺序
1. 先改 ELK
2. 再改鉴权(做减法)

这条路线更符合你的目标: **尽量贴近生产, 但不要给开发和调试增加阻碍**。
