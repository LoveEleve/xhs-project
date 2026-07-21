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

#### 生产者端：契约 DSL 定义

```groovy
// my-xhs-inventory/src/test/resources/contracts/deductStock.groovy
// 库存服务定义"扣减库存"接口的契约
Contract.make {
    description "扣减库存 - 库存充足时成功"
    request {
        method POST()
        url "/api/inventory/deduct"
        headers {
            contentType applicationJson()
        }
        body([
            skuId   : 1001,
            quantity: 2
        ])
    }
    response {
        status 200
        headers {
            contentType applicationJson()
        }
        body([
            code: 200,
            msg : "扣减成功",
            data: [
                skuId        : 1001,
                remainStock  : 98
            ]
        ])
    }
}
```

```groovy
// 库存不足时的契约
Contract.make {
    description "扣减库存 - 库存不足时失败"
    request {
        method POST()
        url "/api/inventory/deduct"
        headers {
            contentType applicationJson()
        }
        body([
            skuId   : 1001,
            quantity: 9999
        ])
    }
    response {
        status 200
        body([
            code: 500,
            msg : "库存不足"
        ])
    }
}
```

#### 消费者端：引用 Stub 测试

```java
/**
 * 订单服务消费者端契约测试
 * 引用库存服务的 Stub，验证 Feign 调用兼容性
 */
@SpringBootTest
@AutoConfigureStubRunner(
    ids = "com.myxhs:my-xhs-inventory:+:stubs:8090",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL
)
class InventoryFeignClientContractTest {

    @Autowired
    private InventoryFeignClient inventoryClient;

    @Test
    void deductStock_shouldSuccess_whenStockEnough() {
        // Given: Stub 自动启动在 8090 端口，返回契约定义的响应
        DeductRequest request = new DeductRequest(1001L, 2);

        // When
        R<DeductResult> result = inventoryClient.deduct(request);

        // Then: 验证响应与契约一致
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getRemainStock()).isEqualTo(98);
    }

    @Test
    void deductStock_shouldFail_whenStockNotEnough() {
        DeductRequest request = new DeductRequest(1001L, 9999);
        R<?> result = inventoryClient.deduct(request);
        assertThat(result.getCode()).isEqualTo(500);
        assertThat(result.getMsg()).isEqualTo("库存不足");
    }
}
```

### 3.4 E2E 端到端测试（核心链路）

```java
/**
 * 核心链路 E2E 测试：注册 → 登录 → 发布笔记 → 下单 → 支付
 * 使用 RestAssured 驱动，测试完整业务流程
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreFlowE2ETest {

    private static String accessToken;
    private static Long userId;
    private static Long noteId;
    private static Long orderId;

    @Test
    @Order(1)
    void step1_register() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", "e2e_test_" + System.currentTimeMillis(),
                "password", "Test@123456",
                "phone", "138" + RandomStringUtils.randomNumeric(8),
                "captchaKey", "test-key",
                "captchaCode", "1234"
            ))
        .when()
            .post("/api/user/register")
        .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.userId", notNullValue());
    }

    @Test
    @Order(2)
    void step2_login() {
        Response response = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", "e2e_test_user",
                "password", "Test@123456",
                "captchaKey", "test-key",
                "captchaCode", "1234"
            ))
        .when()
            .post("/api/user/login");

        accessToken = response.jsonPath().getString("data.accessToken");
        assertThat(accessToken).isNotBlank();
    }

    @Test
    @Order(3)
    void step3_publishNote() {
        Response response = given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + accessToken)
            .body(Map.of(
                "title", "E2E测试笔记",
                "content", "这是一篇E2E测试笔记内容",
                "noteType", 0
            ))
        .when()
            .post("/api/note/publish");

        noteId = response.jsonPath().getLong("data.noteId");
        assertThat(noteId).isNotNull();
    }

    @Test
    @Order(4)
    void step4_createOrder() {
        Response response = given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + accessToken)
            .body(Map.of(
                "skuItems", List.of(Map.of("skuId", 1001, "quantity", 1)),
                "addressId", 100001,
                "bizIdentifier", UUID.randomUUID().toString()
            ))
        .when()
            .post("/api/order/create");

        orderId = response.jsonPath().getLong("data.orderId");
        assertThat(orderId).isNotNull();
    }
}
```

### 3.5 测试覆盖率配置（JaCoCo）

```xml
<!-- pom.xml — JaCoCo 插件配置 -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.11</version>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <!-- 行覆盖率 ≥ 80% -->
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                            <!-- 分支覆盖率 ≥ 70% -->
                            <limit>
                                <counter>BRANCH</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.70</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
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
