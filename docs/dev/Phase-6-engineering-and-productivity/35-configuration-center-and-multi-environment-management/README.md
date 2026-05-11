# 配置中心与多环境管理

> 所属维度：工程规范 | 开发阶段：Phase-6 | 核心组件：Nacos Config

---

## 🎯 一、Nacos配置分组

```
nacos-config/
├── my-xhs-common.yml          # 公共配置(所有服务共享)
├── my-xhs-user-dev.yml        # 用户服务开发环境
├── my-xhs-user-test.yml       # 用户服务测试环境
├── my-xhs-user-prod.yml       # 用户服务生产环境
├── my-xhs-order-dev.yml
├── ...
└── sentinel-rules/            # Sentinel规则(单独目录)
    ├── flow-rules.json
    └── degrade-rules.json
```

| 配置项 | Group | 说明 |
|--------|-------|------|
| 公共配置 | `COMMON_GROUP` | 数据库连接池、Redis配置、MQ配置 |
| 服务配置 | `SERVICE_GROUP` | 各服务私有配置 |
| 限流规则 | `SENTINEL_GROUP` | Sentinel流控规则，独立管理 |

---

## 🏗️ 二、多环境隔离

### 2.1 环境划分

| 环境 | Namespace | 用途 |
|------|-----------|------|
| dev | `dev` | 本地开发 |
| test | `test` | 测试环境 |
| pre | `pre` | 预发环境(与生产配置相同，流量隔离) |
| prod | `prod` | 生产环境 |

### 2.2 bootstrap.yml 配置

```yaml
spring:
  application:
    name: my-xhs-order
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVER:localhost:8848}
        namespace: ${NACOS_NAMESPACE:dev}
        group: SERVICE_GROUP
        shared-configs:
          - data-id: my-xhs-common.yml
            group: COMMON_GROUP
            refresh: true
```

---

## 💻 三、配置热更新

### 3.1 @RefreshScope 动态刷新

```java
@RefreshScope
@Configuration
public class DynamicConfig {
    
    @Value("${order.timeout:30}")
    private Integer orderTimeout;  // 支持Nacos热更新，无需重启
    
    @Value("${feature.newCheckout:false}")
    private Boolean newCheckoutEnabled;  // 功能开关
}
```

### 3.2 功能开关

```yaml
# Nacos中配置
feature:
  newCheckout: false    # 新版结算页开关
  hotSearch: true       # 热搜功能开关
  pushSSE: true         # SSE推送开关
```

```java
@Service
public class OrderService {
    @Autowired
    private DynamicConfig config;
    
    public void checkout() {
        if (config.getNewCheckoutEnabled()) {
            // 新版结算逻辑
        } else {
            // 旧版结算逻辑
        }
    }
}
```

---

## ⚖️ 四、方案对比

| 维度 | Nacos Config | Apollo | Spring Cloud Config |
|------|-------------|--------|-------------------|
| 热更新 | ✅ 长轮询实时 | ✅ 实时 | ❌ 需要Bus+MQ |
| 多环境 | ✅ Namespace | ✅ 环境+集群 | ✅ Profile |
| 权限管理 | ⚠️ 基础 | ✅ 完善 | ❌ 无 |
| 运维成本 | 低(与注册中心共用) | 中(独立部署) | 中(依赖Git) |

**最终选择**：Nacos Config — 与注册中心共用一套，减少运维成本，热更新开箱即用。

---

## 🎤 五、面试考察点

### Q1: 配置中心怎么做的？多环境怎么隔离？

**推荐回答思路**：

> 1. "Nacos Config，Namespace隔离环境(dev/test/pre/prod)"
> 2. "Group分组：COMMON_GROUP公共配置+SERVICE_GROUP服务私有配置+SENTINEL_GROUP限流规则"
> 3. "@RefreshScope支持配置热更新，功能开关不需要重启服务"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §15 | 配置中心规范完整方案 |
