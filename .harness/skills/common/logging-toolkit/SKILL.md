---
name: logging-toolkit
stage: 组件封装
description: 日志工具封装——MDC traceId 自动注入、日志脱敏（配置化规则）、动态日志级别、日志文件策略、链路日志格式化
---

# 日志工具封装（logging-toolkit）

## 1. 职责

为项目生成日志基础设施代码，包括 MDC traceId 自动注入、日志脱敏（配置化规则+统一拦截）、动态日志级别 API、日志文件策略（滚动/压缩/保留）、链路日志格式化。**禁止在业务代码中手动处理日志脱敏和 traceId 传递**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/logging-toolkit [trace\|desensitize\|dynamic\|strategy\|all]` |
| 自动加载 | coding-skill 阶段始终加载（日志是所有项目的必选项） |

## 3. 前置条件

- 项目已识别技术栈+日志框架（Java Logback/Log4j2 / Python logging / Go zap）
- 已确定目标包路径（按语言规范）
- 已确定敏感字段列表（至少包含手机号/邮箱/身份证/银行卡/Token/密码）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `trace` | traceId 自动注入 | 无 traceId 或手动传递 |
| `desensitize` | 日志脱敏 | 日志输出中包含明文敏感字段 |
| `dynamic` | 动态日志级别 | 无动态日志级别切换能力 |
| `strategy` | 日志文件策略 | 无日志文件配置或使用默认配置 |
| `all`（默认） | 全部组件 | 首次引入日志基础设施 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/logging/
├── trace/
│   ├── TraceIdFilter.java            — Servlet Filter traceId 注入（从请求头提取或生成）
│   ├── TraceIdInterceptor.java       — Feign/RestTemplate 拦截器（自动透传）
│   └── TraceIdMDCInjector.java       — 线程池 MDC 上下文传递（装饰器模式）
├── desensitize/
│   ├── LogDesensitizer.java          — 日志脱敏器（配置化规则）
│   ├── DesensitizeRule.java          — 脱敏规则定义（字段名+规则类型）
│   └── SensitiveFields.java          — 默认敏感字段列表
├── dynamic/
│   ├── LogLevelController.java       — 动态日志级别 API（REST 接口）
│   └── LogLevelConfig.java           — 日志级别持久化配置
├── strategy/
│   ├── logback-spring.xml            — Logback 配置文件模板
│   └── LogFileStrategy.java          — 文件策略（滚动/压缩/保留天数）
└── config/
    └── LoggingAutoConfig.java        — 自动配置（确保所有组件生效）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `TraceIdFilter`：从请求头 `X-Trace-Id` 提取，不存在则 UUID 生成，注入 MDC 上下文 `traceId`，响应头中回传，请求结束后从 MDC 清除（防止内存泄漏）
- `TraceIdMDCInjector`：线程池提交任务时自动传递 MDC 上下文（包装 Runnable/Callable），子线程池继承父线程 traceId
- `LogDesensitizer`：在日志输出层统一拦截（日志框架的 Layout/Formatter 扩展），配置化规则（字段名+规则类型），不在业务代码中手动脱敏
- 脱敏规则：手机号 `138****1234`、邮箱 `user***@example.com`、身份证 `110********1234`、银行卡 `6222****1234`、密码 `******`、Token `tok_***`
- `LogLevelController`：提供 REST 接口 `GET/PUT /actuator/loggers/{package}` 查看和修改日志级别，支持持久化（重启后保留）
- 日志文件策略：按大小滚动（maxFileSize=500MB），保留最近 30 天，总大小不超过 10GB，压缩归档（.gz），异步写日志（不阻塞业务线程）

### Step 3: 生成测试

```
{basePackage}/logging/
├── trace/TraceIdFilterTest.java
├── trace/TraceIdMDCInjectorTest.java
├── desensitize/LogDesensitizerTest.java
├── dynamic/LogLevelControllerTest.java
└── strategy/LogFileStrategyTest.java
```

测试要求：
- traceId：验证请求入口注入、子线程传递、响应头回传、请求结束后清除
- 脱敏：验证手机号/邮箱/身份证/银行卡/密码/Token 六种规则，验证边界情况
- 动态日志级别：验证 GET/PUT 接口、级别持久化、重启后恢复
- 使用 Mock MVC 测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] traceId 在请求入口自动注入（非手动生成）
   - [ ] 子线程自动传递 MDC 上下文
   - [ ] 日志脱敏在输出层统一拦截（非业务代码中手动脱敏）
   - [ ] 脱敏规则可配置（非硬编码）
   - [ ] 动态日志级别 API 可用
   - [ ] 日志文件滚动策略已配置

## 5. 输出文件清单

```
{targetPackage}/logging/
├── trace/TraceIdFilter.{java|py|go}
├── trace/TraceIdInterceptor.{java|py|go}
├── trace/TraceIdMDCInjector.{java|py|go}
├── desensitize/LogDesensitizer.{java|py|go}
├── desensitize/DesensitizeRule.{java|py|go}
├── desensitize/SensitiveFields.{java|py|go}
├── dynamic/LogLevelController.{java|py|go}
├── dynamic/LogLevelConfig.{java|py|go}
├── strategy/logback-spring.xml
├── strategy/LogFileStrategy.{java|py|go}
├── config/LoggingAutoConfig.{java|py|go}
├── trace/TraceIdFilterTest.{java|py|go}
├── trace/TraceIdMDCInjectorTest.{java|py|go}
├── desensitize/LogDesensitizerTest.{java|py|go}
└── dynamic/LogLevelControllerTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ traceId 在请求入口自动注入
- ✅ 子线程自动传递 MDC 上下文
- ✅ 日志脱敏在输出层统一拦截
- ✅ 脱敏规则可配置
- ✅ 动态日志级别 API 可用
- ❌ 禁止在业务代码中手动传递 traceId
- ❌ 禁止在业务代码中手动脱敏（应在日志输出层统一处理）
- ❌ 禁止日志文件无滚动策略（会打满磁盘）
- ❌ 禁止日志配置使用同步写（应使用异步 Appender）

## 7. 约束

- ❌ 不生成日志告警规则（由监控团队在 Prometheus AlertManager 中配置）
- ❌ 不修改已有的日志配置（只新增，不覆盖）
- ❌ 不引入未确认的日志框架版本（项目中已有的优先复用）
- ❌ 不生成日志采集（Filebeat/Fluentd）配置（由运维团队管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
