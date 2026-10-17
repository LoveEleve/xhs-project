---
name: spring-api-convention
stage: 组件封装
description: Spring API 规范封装——统一响应体、全局异常处理、幂等注解、分页工具、traceId 链路
---

# Spring API 规范封装（spring-api-convention）

## 1. 职责

为 Spring Boot 项目生成 API 规范基础设施代码，包括统一响应体、全局异常处理、幂等注解、分页工具、traceId 链路。**禁止在 Controller 中直接返回 `Map` 或 `ResponseEntity` 裸对象**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/spring-api-convention [response\|exception\|idempotent\|pagination\|trace\|all]` |
| 自动加载 | coding-skill 阶段检测到 `@RestController` / `@RequestMapping` 且无 `ApiResponse` 类时 |

## 3. 前置条件

- 项目已识别为 Spring Boot（存在 `spring-boot-starter-web` 依赖）
- 已确定目标包路径（默认 `{basePackage}.api`）
- 已确定错误码枚举范围（HTTP 4xx/5xx + 业务码 10001+）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `response` | ApiResponse + ErrorCode | 返回值类型为 `Map` / `ResponseEntity` / `String` |
| `exception` | GlobalExceptionHandler | 无 `@ControllerAdvice` 类 |
| `idempotent` | @Idempotent 注解 + 切面 | 存在 POST/PUT/PATCH 接口且无幂等处理 |
| `pagination` | PageRequest + PageResponse | 存在分页查询接口且无统一分页类 |
| `trace` | TraceIdFilter + TraceIdUtil | 日志中无 traceId 字段 |
| `all`（默认） | 全部五个组件 | 首次引入 API 规范 |

### Step 2: 生成代码文件

创建以下文件到 `{basePackage}/api/` 和 `{basePackage}/api/annotation/`：

```
{basePackage}/api/
├── ApiResponse.java           — 统一响应体（code/message/data/traceId/timestamp）
├── ErrorCode.java             — 错误码枚举（200/400/401/403/404/409/422/429/500/503 + 10001+）
├── GlobalExceptionHandler.java — 全局异常处理器
├── PageRequest.java           — 分页请求参数
├── PageResponse.java          — 分页响应体
├── TraceIdFilter.java         — traceId 过滤器
├── TraceIdUtil.java           — traceId 工具（MDC 埋点）
└── annotation/
    ├── Idempotent.java        — 幂等注解
    └── IdempotentAspect.java  — 幂等切面实现
```

**每个文件的关键实现约束**：

- `ApiResponse`：静态工厂方法（success/error/systemError/authError），JSON 序列化兼容（Jackson @JsonInclude）
- `ErrorCode`：HTTP 状态码与业务错误码分开分段，业务码从 10001 开始
- `GlobalExceptionHandler`：分类处理业务异常/参数校验异常/系统异常/未捕获异常，日志包含 traceId，敏感信息脱敏
- `@Idempotent`：key 支持 SpEL 表达式，ttl 可配置，基于 Redis SET NX 实现
- `PageRequest`：page 从 1 开始，size 默认 20 最大 100，sort 字段白名单校验（防 SQL 注入）
- `TraceIdFilter`：从请求头 `X-Trace-Id` 读取，无则 UUID 生成，响应头回写，MDC 自动清理

### Step 3: 生成测试

```
{basePackage}/api/
├── ApiResponseTest.java
├── GlobalExceptionHandlerTest.java
├── IdempotentAspectTest.java
├── PageRequestTest.java
├── PageResponseTest.java
└── TraceIdFilterTest.java
```

测试要求：
- ApiResponse：验证序列化/反序列化，验证静态工厂方法
- GlobalExceptionHandler：Mock 各类异常，验证响应格式和 HTTP 状态码
- IdempotentAspect：验证 SpEL 解析、Redis 加锁、重复请求拦截
- PageRequest：验证参数校验（page/size 边界、sort 白名单）
- TraceIdFilter：验证 traceId 生成、透传、MDC 清理

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有 Controller 返回值改为 `ApiResponse<T>`，无裸 `Map`/`ResponseEntity`
   - [ ] 所有 Controller 异常由 `GlobalExceptionHandler` 托管，无 `try-catch` 自行处理
   - [ ] 所有 POST/PUT/PATCH 接口标记了 `@Idempotent`
   - [ ] 所有分页接口使用 `PageRequest`/`PageResponse`
   - [ ] 日志中包含 `traceId`

## 5. 输出文件清单

```
{basePackage}/api/
├── ApiResponse.java
├── ErrorCode.java
├── GlobalExceptionHandler.java
├── PageRequest.java
├── PageResponse.java
├── TraceIdFilter.java
├── TraceIdUtil.java
└── annotation/
    ├── Idempotent.java
    └── IdempotentAspect.java
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有 Controller 返回 `ApiResponse<T>`
- ✅ 全局异常处理器覆盖所有异常类型
- ✅ 幂等注解在 POST/PUT/PATCH 上使用
- ✅ 分页参数有上限校验
- ✅ traceId 在日志中可查
- ❌ 禁止 Controller 返回裸类型
- ❌ 禁止异常在 Controller 内部被 `try-catch` 吞掉
- ❌ 禁止分页 `size` 无上限

## 7. 约束

- ❌ 不生成与 API 规范无关的代码（如具体业务 Service/Controller 实现）
- ❌ 不修改已有 API 的签名（只新增基础设施，不破坏兼容）
- ❌ 不引入 Redis 依赖（幂等注解需要 Redis，但提示用户自行添加依赖）
- ❌ 不擅自决定错误码枚举值（留出 10001+ 空间由用户定义）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
