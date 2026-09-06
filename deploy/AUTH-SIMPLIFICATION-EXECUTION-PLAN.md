# 鉴权收口执行计划

> 日期: 2026-08-24
> 分支: `refactor/elk-auth-simplify`
> 目标: 在不牺牲核心边界的前提下, 把当前 `JWT + HMAC + X-Admin-Call + X-Internal-Call + GatewayAuthTrustFilter + controller 手工校验`
> 收敛成更简单、统一、对开发/测试更友好的模型。

---

## 一、当前真实结构

当前鉴权链不是一层, 而是 5 层叠加:

1. **GatewayAuthFilter**
   - Bearer JWT 解析
   - JWT 类型检查(access)
   - Redis 黑名单检查
   - 注入 `X-User-Id` / `X-User-Role`

2. **HmacSignatureFilter**
   - 非白名单写操作默认要求 `X-Timestamp / X-Nonce / X-Signature`
   - 依赖 per-session HMAC secret

3. **GatewayAuthTrustFilter**
   - 服务端口直连时:
     - `X-Internal-Call` -> 信任
     - Bearer JWT -> 覆盖 `X-User-Id`
     - 其他 -> 剥离 `X-User-Id`

4. **FeignInternalCallInterceptor**
   - 所有 Feign 自动注入 `X-Internal-Call`

5. **controller 层手工校验**
   - 各服务自己校验 `X-Admin-Call`
   - 各服务自己校验 `X-Internal-Call`

结论:

- 问题不是“没安全”
- 问题是“层次太多、职责重叠、开发测试极其繁琐”

---

## 二、目标模型

## 2.1 外部用户请求

### 保留
- `Authorization: Bearer <access_token>`

### 取消默认强制
- `HMAC` 不再作为普通写接口的默认必需条件

### 目标效果
- 所有用户态接口默认只需要 JWT
- 不再需要每次写接口都额外准备 HMAC 头

---

## 2.2 内部服务调用

### 保留
- `X-Internal-Call`

### 目标效果
- Feign 调用仍然可识别内部身份
- 继续和外部用户 JWT 路径分离
- 但 controller 不再大量散写重复判断

---

## 2.3 管理接口

### 短中期保留
- `X-Admin-Call`

### 原因
- 当前系统并没有成熟角色/权限中心
- 强行切到 JWT role/RBAC 会扩大改动面
- `X-Admin-Call` 更适合当前“开发友好、脚本友好”的目标

### 目标效果
- 管理接口仍然独立于普通用户路径
- 但校验逻辑要统一抽象, 不再每个 controller 手写 if

---

## 2.4 HMAC 的定位

### 新定位
- **可选增强项**, 而不是默认主路径

### 目标效果
- 默认所有普通用户接口不再需要 HMAC
- 如果以后某些极高敏操作要恢复 HMAC, 可以按配置开启

### 原则
- 做减法, 不做删库式暴力移除
- 先从“默认必需”降级到“默认关闭/可选”

---

## 三、要改哪些地方

## 3.1 Gateway

### 需要改
#### `GatewayAuthFilter`
位置:
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/GatewayAuthFilter.java`

需要确认/保留:
- JWT 白名单
- access token 类型校验
- Redis 黑名单
- 注入 `X-User-Id`

这部分应继续保留, 因为它是外部身份主入口。

#### `HmacSignatureFilter`
位置:
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/HmacSignatureFilter.java`

需要改:
- 从“默认全局强制写接口”改成“可选增强层”

推荐做法:
1. 增加总开关配置, 例如:
   - `gateway.auth.hmac-enabled: false`
2. 当关闭时, 直接跳过 HMAC 逻辑
3. 保留白名单配置结构, 但不再作为默认依赖

这样可以:
- 兼顾未来恢复能力
- 当前先大幅降低使用复杂度

#### `AuthProperties`
位置:
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/config/AuthProperties.java`

需要改:
- 增加 `hmacEnabled` 开关
- 保留现有白名单结构, 但语义调整

---

## 3.2 Common 层

### `GatewayAuthTrustFilter`
位置:
- `my-xhs-common/src/main/java/com/myxhs/common/web/GatewayAuthTrustFilter.java`

当前职责是合理的, 但语义应更明确:

保留:
1. 内部调用信任 `X-Internal-Call`
2. Bearer JWT 覆盖 `X-User-Id`
3. 其他情况剥离伪造头

这层不应该删, 因为它是真正防服务端口直连伪造头的底线。

但需要配合后续 controller 收口:
- 减少 controller 对 `X-User-Id` 来源的重复担心

---

## 3.3 Feign 层

### `FeignInternalCallInterceptor`
位置:
- `my-xhs-common/src/main/java/com/myxhs/feign/config/FeignInternalCallInterceptor.java`

结论:
- 继续保留
- 这是当前内部调用的最小成本统一方案

但要做的不是删它, 而是:
- 让 controller 层不再各自重复写很多散乱校验逻辑

### 特殊 Feign config
例如:
- `my-xhs-payment/.../InternalCallFeignConfig.java`

要核查:
- 是否与全局 `FeignInternalCallInterceptor` 重复
- 是否可合并或下沉统一

---

## 3.4 Controller 层

这是改造量最大的部分。

### 当前问题
很多 controller 里都手工做:
- `isAdminCall(...)`
- `isInternalCall(...)`
- 或手工读 `@RequestHeader("X-User-Id")`

### 目标
把 controller 从“自己做安全判断”改成“声明自己需要什么身份”。

### 推荐两步走

#### 第一步: 先统一模式, 不做框架化过深抽象
- 普通用户接口: 只依赖 `X-User-Id`
- 内部接口: 统一用一个 helper / base method 校验 `X-Internal-Call`
- 管理接口: 统一用一个 helper / base method 校验 `X-Admin-Call`

#### 第二步: 再考虑注解化
如果第一步稳定, 可再考虑:
- `@InternalOnly`
- `@AdminOnly`

但不建议第一轮就上太重抽象。

---

## 四、建议实施顺序

## Phase 1: Gateway 先减负
1. 给 HMAC 增加总开关
2. 默认关闭 HMAC 强制校验
3. 保留 HMAC 代码, 不删
4. 保证 JWT 主路径正常

### 验证目标
- 原来需要 `JWT + HMAC` 的用户写接口, 现在只用 JWT 即可
- 不影响公开白名单和黑名单逻辑

---

## Phase 2: controller 收口
1. 盘点所有 `X-Admin-Call` 接口
2. 盘点所有 `X-Internal-Call` 接口
3. 提取统一 helper, 去掉重复 if/equals
4. 特殊 Feign config 去重

### 已完成的代表服务（2026-08-24）
- `my-xhs-user` controller
- `my-xhs-product` controller
- `my-xhs-coupon` controller
- `my-xhs-inventory` controller
- `my-xhs-payment` controller
- `my-xhs-search` controller
- `my-xhs-counter` controller
- `my-xhs-notification` controller
- `my-xhs-im` controller
- `my-xhs-analytics` controller
- `my-xhs-cart` controller
- `my-xhs-order` controller
- `my-xhs-search` RecommendController

这些服务已切到 `my-xhs-common` 中统一的 `AccessTokenGuard`，说明收口模式可行，且不改变现有权限语义。

### 内容服务构建前置已修复（2026-08-24）
- 仓库中确实不存在 `my-xhs-content-api` 模块；
- `my-xhs-content/pom.xml` 中对该模块的无效依赖已移除；
- `my-xhs-content` 已重新编译通过。

### 验证目标
- 外部用户接口调用更简单
- 内部接口仍然受保护
- 管理接口仍然受保护

---

## Phase 3: 文档与测试口径重写
需要同步更新:
- `docs/test-2/TEST-REFERENCE-V2.md`
- `docs/test-3/TEST-REFERENCE-V2.md`
- 各类手工测试脚本
- 说明不再默认要求 HMAC

---

## 五、风险与注意点

## 5.1 最大风险
- 误把本来只应内部可调的接口暴露成普通用户可调

所以在收口时必须明确区分:
1. 用户态接口
2. 内部服务接口
3. 管理接口

## 5.2 不要做的事
1. 不要一次性删掉 `GatewayAuthTrustFilter`
2. 不要一次性删掉所有 `X-Internal-Call`
3. 不要第一轮就搞复杂 RBAC 系统
4. 不要把 HMAC 代码彻底删除

## 5.3 应保留的底线
- JWT 黑名单能力
- 内部服务身份能力
- 管理接口独立保护能力
- 服务端口伪造头剥离能力

---

## 六、最终建议

### 这轮真正应该做的
1. **把 HMAC 从默认主路径降级**
2. **让 JWT 成为外部唯一主路径**
3. **保留并统一 `X-Internal-Call`**
4. **保留并统一 `X-Admin-Call`**
5. **减少 controller 层重复手写校验**

### 不该做的
1. 不做完整 IAM/RBAC 大工程
2. 不做安全增强加法
3. 不做过度抽象框架化

### 一句话结论
- 这次鉴权改造不是“更安全”
- 而是“更统一、更轻、更不烦人，同时底线还在”
