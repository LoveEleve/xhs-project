# 测试策略与质量保障

> 所属维度：工程实践 | 开发阶段：Phase-6 | 核心工具：JUnit5 + Mockito + Testcontainers + Spring Cloud Contract

---

## 🎯 一、测试金字塔

```
                    ┌─────────┐
                    │  E2E    │  ← 端到端测试(少量关键链路)
                   ╱│  Test   │╲
                  ╱ └─────────┘ ╲
                 ╱               ╲
                ╱  ┌───────────┐  ╲
               ╱   │Integration│   ╲  ← 集成测试(服务间调用)
              ╱    │   Test    │    ╲
             ╱     └───────────┘     ╲
            ╱                         ╲
           ╱    ┌─────────────────┐    ╲
          ╱     │   Unit Test     │     ╲  ← 单元测试(最多)
         ╱      └─────────────────┘      ╲
        ╱─────────────────────────────────╲
```

---

## 📋 二、测试覆盖要求

| 测试类型 | 覆盖目标 | 工具 | 运行时机 |
|----------|----------|------|----------|
| 单元测试 | 核心业务逻辑 ≥ 80% | JUnit5 + Mockito | 每次提交 |
| 集成测试 | 数据库/Redis/MQ操作 | Testcontainers | 每次PR |
| 契约测试 | 服务间接口兼容性 | Spring Cloud Contract | 每次发布 |
| E2E测试 | 核心业务链路 | Playwright/Selenium | 每次发布 |

---

## 💻 三、核心实现

### 3.1 单元测试（JUnit5 + Mockito）

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private InventoryFeignClient inventoryClient;
    
    @InjectMocks
    private OrderServiceImpl orderService;
    
    @Test
    void createOrder_shouldSuccess() {
        // Given
        OrderCreateCmd cmd = new OrderCreateCmd(/*...*/);
        when(inventoryClient.deduct(any())).thenReturn(true);
        
        // When
        Long orderId = orderService.createOrder(cmd);
        
        // Then
        assertNotNull(orderId);
        verify(orderMapper).insert(any());
        verify(inventoryClient).deduct(any());
    }
}
```

### 3.2 集成测试（Testcontainers）

```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("my_xhs_order");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
    }
    
    @Test
    void createOrder_shouldSuccess() {
        // 真实的MySQL和Redis容器
        // 测试完整的下单流程
    }
}
```

### 3.3 契约测试（Spring Cloud Contract）

```
生产者(库存服务)定义契约 → 自动生成Stub
消费者(订单服务)引用Stub → 验证接口兼容性

好处：
- 库存服务接口变更时，订单服务的契约测试会失败
- 在发布前发现服务间接口不兼容问题
- 不需要启动所有服务，只需要Stub
```

---

## ⚖️ 四、方案对比

| 维度 | Testcontainers | H2内存数据库 | Mock全部依赖 |
|------|---------------|-------------|-------------|
| 真实性 | 高(真实MySQL/Redis) | 中(SQL方言差异) | 低(不验证IO) |
| 速度 | 慢(启动容器) | 快 | 最快 |
| 维护成本 | 低(Docker自动管理) | 中(SQL兼容问题) | 高(Mock过多) |
| 适用场景 | 集成测试 | 简单DAO测试 | 单元测试 |

**最终选择**：单元测试用Mockito、集成测试用Testcontainers、契约测试用Spring Cloud Contract — 三层互补。

---

## 🎤 五、面试考察点

### Q1: 你们的测试策略是什么？

**推荐回答思路**：

> 1. "测试金字塔：单元测试(80%覆盖率)→集成测试(Testcontainers)→契约测试→E2E测试"
> 2. "集成测试用Testcontainers启动真实的MySQL和Redis容器，避免H2和MySQL的SQL方言差异"
> 3. "服务间接口用Spring Cloud Contract契约测试，发布前自动验证兼容性"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §17 | 测试策略完整方案 |
