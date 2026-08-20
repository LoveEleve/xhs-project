# F-013 AI 接入的会话归属与取消权限校验缺失

## 严重度

High

## 涉及文件

- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/controller/ConversationController.java:33-51`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/controller/ConversationController.java:53-68`
- `my-xhs-ai-app/src/main/java/com/myxhs/ai/app/controller/RunController.java:83-85`

## 现象

AI 诊断台的会话与任务端点存在身份归属与权限校验缺失：

1. `GET /api/conversations?userId=` 只认 query 参数 `userId`，不消费网关注入的 `X-User-Id`。
2. `GET /api/conversations/{convId}` 按 convId 直接返回会话详情与消息，无用户归属校验。
3. `DELETE /api/runs/{runId}` 无角色校验，任何登录用户（含 L1/OPERATOR）都能取消任意诊断任务。

## 证据

1. `ConversationController.list()` 只从 `@RequestParam userId` 读取，默认 "anonymous"：`ConversationController.java:54`。
2. `ConversationController.get()` 直接 `conversationService.get(convId)`，不校验当前用户是否是该会话 owner：`ConversationController.java:33-36`。
3. `RunController.cancel()` 只调用 `runManager.cancel(runId)`，无 `X-User-Role` 校验：`RunController.java:83-85`。
4. 对照 `AiQueryController` 与 `RunController` 的提交路径，已实现 `X-User-Id` header 优先（`AiQueryController.java:97-98`、`RunController.java:61-62`），但会话列表/详情与取消路径未对齐。

## 触发条件

1. 攻击者知道目标用户的 `userId`，调用 `GET /api/conversations?userId=<target>` 查看其会话列表。
2. 攻击者获得或猜测到 `convId`，调用 `GET /api/conversations/{convId}` 读取会话消息。
3. 任意登录用户调用 `DELETE /api/runs/{runId}` 取消他人诊断任务。

## 影响

1. 会话列表与详情可被跨用户读取，泄露诊断内容与用户上下文。
2. 取消任务无角色边界，L1 用户可干扰 L2 诊断流程。
3. 与网关已注入的 `X-User-Id`/`X-User-Role` 信任模型不一致，削弱统一鉴权。

## 修复建议

1. `ConversationController.list()` 改为 `X-User-Id` header 优先，query 仅作开发兜底。
2. `ConversationController.get()` 增加归属校验：仅返回当前用户拥有的会话。
3. `RunController.cancel()` 增加 `X-User-Role` 校验，非 TECH 返回 403。
4. 与 AI 团队对齐，统一所有 AI 端点的身份来源与权限边界。

## 残余风险

`convId` 为不可枚举的随机 ID，降低了直接枚举风险，但归属校验缺失仍是设计缺陷；取消任务的角色边界缺失是明确的权限漏洞。

## 是否需要补充验证

需要验证：跨用户 `userId` 查询会话列表、跨用户 `convId` 读取会话、L1 用户取消任务，三者当前是否均可成功。