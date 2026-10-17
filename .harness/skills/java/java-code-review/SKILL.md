---
name: java-code-review
stage: 组件封装
description: Java 并发编程工具封装——线程池工厂、锁模板、并发操作模板、异步处理模板
---

# Java 并发编程工具封装（java-code-review）

## 1. 职责

为 Java 项目生成生产级并发编程基础设施代码，包括线程池工厂、分布式锁模板、并发操作模板（重试/限流/熔断/批量并行）。**禁止直接 `new ThreadPoolExecutor()` 或使用 `Executors` 默认工厂**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/java-code-review [threadpool\|lock\|concurrent\|all]` |
| 自动加载 | coding-skill 阶段检测到 `ThreadPoolExecutor` / `Executors` / `synchronized` / `Lock` 时 |

## 3. 前置条件

- 项目已识别为 Java（存在 `pom.xml` / `build.gradle`）
- 已确定目标包路径（默认 `{basePackage}.concurrent`）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

按用户子命令或自动检测结果生成以下组件：

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `threadpool` | ThreadPoolFactory | 代码中出现 `new ThreadPoolExecutor` / `Executors.newFixedThreadPool` |
| `lock` | LockTemplate | 出现 `synchronized` / `ReentrantLock` / 分布式锁需求 |
| `concurrent` | ConcurrentTemplate | 出现重试/限流/熔断/批量并行逻辑 |
| `all`（默认） | 全部三个组件 | 首次引入并发基础设施 |

### Step 2: 生成代码文件

创建以下文件到 `{basePackage}/concurrent/`：

```
{basePackage}/concurrent/
├── ThreadPoolFactory.java      — 线程池工厂（带监控 + 优雅关闭）
├── ThreadPoolConfig.java       — 线程池配置参数类
├── NamedThreadFactory.java     — 可命名线程工厂
├── LockTemplate.java           — 锁接口（本地锁 + 分布式锁统一接口）
├── RedisLockTemplate.java      — Redis 分布式锁实现（Lua 释放 + 看门狗）
├── ConcurrentTemplate.java     — 并发操作模板（重试/限流/熔断/批量并行）
├── RetryConfig.java            — 重试配置
├── RateLimitConfig.java        — 限流配置
└── CircuitBreaker.java         — 熔断器（CLOSED/OPEN/HALF_OPEN 状态机）
```

**每个文件的关键实现约束**：

- `ThreadPoolFactory`：禁止无限队列，必须设容量；注册到全局 Map 以便优雅关闭；暴露 Micrometer/Prometheus 指标
- `RedisLockTemplate`：加锁用 `SET key value NX PX timeout`；释放用 Lua 脚本校验 value；看门狗自动续期
- `ConcurrentTemplate.retry()`：指数退避 + 最大重试次数 + 可重试异常白名单
- `CircuitBreaker`：基于滑动窗口统计失败率，半开状态放行试探请求

### Step 3: 生成测试

为每个核心组件生成单元测试：

```
{basePackage}/concurrent/
├── ThreadPoolFactoryTest.java
├── RedisLockTemplateTest.java
├── ConcurrentTemplateTest.java
└── CircuitBreakerTest.java
```

测试要求：
- ThreadPoolFactory：验证创建、监控、优雅关闭（超时兜底）
- RedisLockTemplate：验证加锁/释放/Lua 脚本/看门狗续期
- ConcurrentTemplate：验证重试次数、退避间隔、熔断器状态转换
- 所有测试使用 Mock 外部依赖（Redis 用 embedded Redis 或 Mock）

### Step 4: 验证

1. 编译通过：`mvn compile` 或 `gradle compileJava`
2. 测试通过：`mvn test` 或 `gradle test`
3. 检查清单：
   - [ ] 所有 `ThreadPoolExecutor` 使用工厂创建，非直接 `new`
   - [ ] 所有 `Executors.*` 调用被替换为工厂
   - [ ] 分布式锁释放使用 Lua 脚本
   - [ ] 重试逻辑有最大次数限制（无无限重试）
   - [ ] 熔断器有半开状态（非简单的失败计数）

## 5. 输出文件清单

```
{basePackage}/concurrent/
├── ThreadPoolFactory.java
├── ThreadPoolConfig.java
├── NamedThreadFactory.java
├── LockTemplate.java
├── RedisLockTemplate.java
├── ConcurrentTemplate.java
├── RetryConfig.java
├── RateLimitConfig.java
├── CircuitBreaker.java
├── ThreadPoolFactoryTest.java
├── RedisLockTemplateTest.java
├── ConcurrentTemplateTest.java
└── CircuitBreakerTest.java
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有 `Executors.*` 被清理
- ✅ 分布式锁使用 Lua 释放
- ✅ 重试有上限
- ✅ 熔断器有半开状态
- ❌ 禁止线程池使用无限队列
- ❌ 禁止锁释放时用 `if-else` 代替 Lua
- ❌ 禁止重试无上限

## 7. 约束

- ❌ 不生成与并发无关的工具类（如 StringUtils、DateUtils 等）
- ❌ 不修改已有业务代码，只生成基础设施组件
- ❌ 不引入未经确认的外部依赖（如 Redisson，除非项目中已存在）
- ❌ 不擅自决定监控对接方式（预留 SPI 接口，由用户选择实现）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
