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

### 3.3 配置变更监听器（实时感知 + 审计日志）

```java
/**
 * Nacos 配置变更监听器
 * 监听配置变更事件，记录审计日志 + 触发业务回调
 */
@Component
@Slf4j
public class NacosConfigChangeListener {

    @Value("${spring.application.name}")
    private String applicationName;

    @Autowired
    private ConfigChangeLogMapper configChangeLogMapper;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @NacosConfigListener(dataId = "${spring.application.name}.yml", groupId = "SERVICE_GROUP")
    public void onConfigChange(String newConfig) {
        String dataId = applicationName + ".yml"; // 从应用名拼接 dataId
        log.info("检测到配置变更，dataId={}, 新配置长度: {}", dataId, newConfig.length());

        // 1. 记录配置变更审计日志
        ConfigChangeLog changeLog = new ConfigChangeLog();
        changeLog.setDataId(dataId);
        changeLog.setNewContent(newConfig);
        changeLog.setChangeTime(LocalDateTime.now());
        changeLog.setSource("nacos"); // 来源：nacos / git
        configChangeLogMapper.insert(changeLog);

        // 2. 触发业务回调（如 Sentinel 规则刷新、缓存清理等）
        applicationEventPublisher.publishEvent(
                new ConfigChangedEvent(this, dataId, newConfig));
    }

    /**
     * 监听 Sentinel 限流规则变更
     */
    @NacosConfigListener(dataId = "flow-rules.json", groupId = "SENTINEL_GROUP")
    public void onSentinelRuleChange(String newRules) {
        log.info("Sentinel 限流规则变更，重新加载...");
        List<FlowRule> rules = JSON.parseArray(newRules, FlowRule.class);
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 限流规则加载完成，规则数: {}", rules.size());
    }
}
```

### 3.4 敏感配置加密（Jasypt）

```yaml
# bootstrap.yml — Jasypt 加密配置
jasypt:
  encryptor:
    password: ${JASYPT_PASSWORD}  # 加密密钥从环境变量读取，不写在配置文件中
    algorithm: PBEWithMD5AndDES

# Nacos 中的加密配置示例
spring:
  datasource:
    # ENC() 包裹的值会被 Jasypt 自动解密
    password: ENC(dGhpcyBpcyBhbiBlbmNyeXB0ZWQgcGFzc3dvcmQ=)
  redis:
    password: ENC(cmVkaXMxMjM=)
```

```java
/**
 * Jasypt 加密工具 — 用于生成加密后的配置值
 * 使用方式：java -cp jasypt.jar org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI
 *           input="redis123" password="mySecretKey" algorithm=PBEWithMD5AndDES
 */
@Component
public class ConfigEncryptUtil {

    @Autowired
    private StringEncryptor encryptor;

    /**
     * 加密敏感配置值
     */
    public String encrypt(String plainText) {
        return encryptor.encrypt(plainText);
    }

    /**
     * 解密（框架自动调用，一般不需要手动调用）
     */
    public String decrypt(String encryptedText) {
        return encryptor.decrypt(encryptedText);
    }
}
```

### 3.5 多环境配置隔离实践

```yaml
# bootstrap.yml — 多环境自动切换
spring:
  application:
    name: my-xhs-order
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}  # 默认 dev，部署时通过环境变量覆盖
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVER:localhost:8848}
        namespace: ${NACOS_NAMESPACE:dev}  # dev/test/pre/prod
        group: SERVICE_GROUP
        file-extension: yml
        shared-configs:
          - data-id: my-xhs-common.yml
            group: COMMON_GROUP
            refresh: true
        extension-configs:
          - data-id: sentinel-flow-rules.json
            group: SENTINEL_GROUP
            refresh: true
```

```java
/**
 * 环境感知配置 — 不同环境使用不同策略
 * 例如：dev 环境使用 MockPayService，prod 环境使用 AlipayService
 */
@Configuration
public class EnvironmentAwareConfig {

    @Bean
    @Profile("dev")
    public PayService mockPayService() {
        return new MockPayService(); // 开发环境：Mock 支付
    }

    @Bean
    @Profile("prod")
    public PayService alipayService() {
        return new AlipayService(); // 生产环境：真实支付
    }

    @Bean
    @Profile("dev")
    public FileStorageService localFileStorage() {
        return new LocalFileStorageService(); // 开发环境：本地磁盘
    }

    @Bean
    @Profile("prod")
    public FileStorageService minioFileStorage() {
        return new MinioFileStorageService(); // 生产环境：MinIO
    }
}
```

### 3.6 配置灰度发布

```java
/**
 * 配置灰度发布 — 新配置先在部分实例生效，验证无误后全量推送
 * 
 * 实现原理：
 * 1. Nacos 配置中增加 grayInstances 字段，指定灰度实例 IP
 * 2. 配置变更监听器判断当前实例是否在灰度列表中
 * 3. 灰度实例使用新配置，非灰度实例使用旧配置
 */
@Component
public class GrayConfigManager {

    @Value("${server.ip:#{T(java.net.InetAddress).getLocalHost().getHostAddress()}}")
    private String currentIp;

    @Value("${gray.instances:}")  // 灰度实例 IP 列表，逗号分隔
    private String grayInstances;

    @Autowired
    private NacosConfigService nacosConfigService;

    /**
     * 判断当前实例是否在灰度列表中
     */
    public boolean isGrayInstance(String grayInstances) {
        if (StringUtils.isBlank(grayInstances)) return false;
        Set<String> graySet = Set.of(grayInstances.split(","));
        return graySet.contains(currentIp);
    }

    /**
     * 获取配置值（灰度感知）
     * 如果当前实例在灰度列表中，使用 gray 前缀的配置
     * 否则使用默认配置
     */
    public String getConfigValue(String key, String defaultValue) {
        String grayKey = "gray." + key;
        String grayValue = nacosConfigService.getConfig(grayKey);
        if (grayValue != null && isGrayInstance(this.grayInstances)) {
            return grayValue;
        }
        return nacosConfigService.getConfig(key, defaultValue);
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
