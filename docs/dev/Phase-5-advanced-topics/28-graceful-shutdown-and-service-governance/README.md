# 优雅停机与服务治理

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、为什么需要优雅停机

```
问题：K8s 滚动更新杀 Pod 时，正在处理的请求被中断
结果：用户下单请求处理到一半被杀 → 扣了库存但没创建订单 → 数据不一致

优雅停机 = 先下线 → 再等待 → 最后退出
```

---

## 🏗️ 二、优雅停机三步曲

```
Step 1: Nacos 注销（不再接新请求）
  ↓ PreStop 钩子触发
Step 2: 等待现有请求完成（最长 30 秒）
  ↓ server.shutdown=graceful
Step 3: JVM 退出
  ↓ ShutdownHook 执行清理
```

### 2.1 Spring Boot 配置

```yaml
server:
  shutdown: graceful  # 优雅停机
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 最长等 30 秒
```

### 2.2 K8s 配置

```yaml
spec:
  terminationGracePeriodSeconds: 60  # K8s 等待 60 秒
  containers:
    - lifecycle:
        preStop:
          exec:
            command:
              - /bin/sh
              - -c
              - "curl -X PUT http://localhost:8080/actuator/service-registry?status=DOWN && sleep 10"
              # 先从 Nacos 下线 → 等 10 秒（让其他服务感知下线）→ Spring 优雅停机
      livenessProbe:
        httpGet:
          path: /actuator/health/liveness
          port: 8080
        initialDelaySeconds: 30
        periodSeconds: 10
      readinessProbe:
        httpGet:
          path: /actuator/health/readiness
          port: 8080
        initialDelaySeconds: 10
        periodSeconds: 5
```

---

## 💻 三、服务治理

### 3.1 异常分级处理

| 异常类型 | 日志级别 | 告警 | 示例 |
|---------|---------|------|------|
| BizException | WARN | 不告警 | 参数校验失败、库存不足 |
| SysException | ERROR | P1 告警 | NPE、DB 连接失败 |
| RemoteException | ERROR | P1 告警 | Feign 调用超时 |
| 未知异常 | ERROR | P0 告警 | OOM、StackOverflow |

### 3.2 Feign 超时与重试

```yaml
feign:
  client:
    config:
      default:
        connect-timeout: 5000   # 连接超时 5 秒
        read-timeout: 10000     # 读超时 10 秒
  # 全局关闭重试！非幂等接口重试会导致重复操作
  # 例：下单接口重试 → 创建两个订单
```

### 3.3 降级预案

| 预案 | 触发条件 | 降级策略 |
|------|---------|---------|
| Redis 不可用 | Redis 连接失败 | 降级查 DB + 限流（QPS 降到 1/10） |
| DB 不可用 | MySQL 连接失败 | 返回缓存数据 + 写操作排队 |
| MQ 不可用 | RocketMQ 不可用 | 本地消息表兜底 + 定时补发 |
| 下游服务不可用 | Feign 超时 | Fallback 返回默认值 |

---

## 🐛 四、踩坑记录

### 4.1 Nacos 下线延迟导致请求打到已停止的实例

- **现象**：服务已停止但其他服务仍调用它
- **原因**：Nacos 注册表有缓存（默认 30 秒刷新）
- **解决**：PreStop 先下线 → sleep 10 秒（等其他服务刷新注册表）→ 再停机

### 4.2 Feign 重试导致重复下单

- **现象**：用户下了 2 个相同订单
- **原因**：Feign 默认重试 + 下单接口非幂等
- **解决**：全局关闭 Feign 重试（NEVER_RETRY），幂等接口用 @Idempotent 保护

### 4.3 ShutdownHook 执行顺序不确定

- **现象**：Buffer-Trigger 的 ShutdownHook 未执行完就退出了
- **解决**：Spring @PreDestroy 替代 Runtime.addShutdownHook，Spring 管理执行顺序

---

## 🎤 五、面试考察点

### Q1: K8s 滚动更新时怎么保证请求不丢？

> 1. "PreStop 钩子先从 Nacos 注销 → 不再接新请求"
> 2. "sleep 10 秒等其他服务刷新注册表"
> 3. "server.shutdown=graceful 等待现有请求完成（最长 30 秒）"
> 4. "terminationGracePeriodSeconds=60 给足时间"

### Q2: Feign 调用超时了怎么处理？

> 1. "连接超时 5 秒 + 读超时 10 秒"
> 2. "全局关闭重试（NEVER_RETRY）——非幂等接口重试会重复操作"
> 3. "Fallback 降级返回默认值"
> 4. "幂等接口用 @Idempotent 保护，即使重试也不会重复"

### Q3: 线上出问题了怎么快速降级？

> 1. "预案化：Redis/DB/MQ/下游服务各有独立降级预案"
> 2. "一键切换：Nacos 配置中心修改降级开关，实时生效"
> 3. "分级降级：先降非核心功能（推荐/搜索），保核心链路（下单/支付）"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.28 | 优雅停机与服务治理完整设计 |
| 📄 02-module-detailed-design.md §1 | common 模块优雅停机配置 |
| 📄 36-high-availability-and-fault-contingency | 高可用与故障预案 |
