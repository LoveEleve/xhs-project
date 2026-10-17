---
name: redis-cache-wrapper
stage: 组件封装
description: 多级缓存封装——穿透/击穿/雪崩防护、分布式锁、本地缓存 + Redis 双级缓存
---

# 多级缓存封装（redis-cache-wrapper）

## 1. 职责

为项目生成多级缓存基础设施代码，包括缓存服务（L1 本地 + L2 Redis + L3 DB）、分布式锁、热 key 探测。**禁止直接操作 Redis 客户端（get/set）而不经过缓存服务层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/redis-cache-wrapper [cache\|lock\|all]` |
| 自动加载 | coding-skill 阶段检测到 `Redis` / `Memcached` / 缓存操作代码时 |

## 3. 前置条件

- 项目已识别技术栈（Java/Python/Go 等）
- 已确定目标包路径（按语言规范：Java `{basePackage}.cache`，Python `{app}/cache/`，Go `internal/cache/`）
- 已确定缓存中间件类型（Redis/Memcached/本地缓存）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 生成文件数 |
|--------|------|-----------|
| `cache` | MultiLevelCacheService | 3~5 个文件 |
| `lock` | DistributedLock | 2~3 个文件 |
| `all`（默认） | 全部组件 | 5~8 个文件 |

### Step 2: 生成代码文件

生成以下文件到目标包路径：

```
# Java 示例
{basePackage}/cache/
├── CacheService.java           — 缓存服务接口（get/set/delete/getWithLoader）
├── MultiLevelCacheService.java — 多级缓存实现（L1→L2→L3 逐级穿透）
├── BloomFilter.java            — 布隆过滤器（穿透防护）
├── DistributedLock.java        — 分布式锁接口
├── RedisDistributedLock.java   — Redis 分布式锁实现
├── HotKeyDetector.java         — 热 key 探测（本地频率统计 + 自动降级）
└── CacheConfig.java            — 缓存配置（TTL/L1 上限/布隆过滤器容量）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `CacheService`：L1 本地缓存（Caffeine/Guava/LRU）+ L2 Redis + L3 DB 回填，逐级穿透
- 穿透防护：DB 查不到→缓存空值（短 TTL 30s）+ 布隆过滤器前置拦截
- 击穿防护：互斥锁（SETNX）只让一个请求重建缓存 + 永不过期+异步刷新
- 雪崩防护：TTL 加随机偏移 + L1 不依赖 Redis（Redis 挂了本地缓存仍可用） + 熔断降级
- `DistributedLock`：加锁 SET NX PX，释放 Lua 脚本，看门狗续期，可重入
- `HotKeyDetector`：本地滑动窗口统计 key 访问频率，超过阈值自动降级到 L1

### Step 3: 生成测试

```
# Java 示例
{basePackage}/cache/
├── MultiLevelCacheServiceTest.java
├── RedisDistributedLockTest.java
└── HotKeyDetectorTest.java
```

测试要求：
- 缓存穿透：验证空值缓存 + 布隆过滤器拦截
- 缓存击穿：验证并发下只有一个请求重建缓存
- 分布式锁：验证加锁/释放/看门狗续期/可重入
- 热 key 探测：验证频率统计阈值触发自动降级
- 使用 Mock/嵌入式 Redis 测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 缓存操作全部经过 `CacheService`，无直接 Redis 客户端操作
   - [ ] 布隆过滤器已前置部署（非穿透后才查布隆）
   - [ ] TTL 包含随机偏移（非固定值）
   - [ ] 分布式锁释放使用 Lua 脚本
   - [ ] 热 key 探测有阈值配置（非硬编码）

## 5. 输出文件清单

```
{targetPackage}/cache/
├── CacheService.{java|py|go}
├── MultiLevelCacheService.{java|py|go}
├── BloomFilter.{java|py|go}
├── DistributedLock.{java|py|go}
├── RedisDistributedLock.{java|py|go}
├── HotKeyDetector.{java|py|go}
├── CacheConfig.{java|py|go}
├── MultiLevelCacheServiceTest.{java|py|go}
├── RedisDistributedLockTest.{java|py|go}
└── HotKeyDetectorTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有缓存操作经过 `CacheService` 封装
- ✅ 穿透/击穿/雪崩三种防护均已实现
- ✅ 分布式锁释放使用 Lua 脚本
- ✅ TTL 带有随机偏移
- ❌ 禁止在业务代码中直接操作 Redis 客户端
- ❌ 禁止缓存没有 TTL
- ❌ 禁止分布式锁释放用 `if-else` 代替 Lua

## 7. 约束

- ❌ 不生成缓存监控面板代码（只预留指标暴露接口）
- ❌ 不引入未确认的缓存中间件依赖（项目中已存在的优先复用）
- ❌ 不擅自决定 L1 缓存库（用项目已有库，无则用推荐但不强制）
- ❌ 不修改已有业务代码（只新增缓存基础设施）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
