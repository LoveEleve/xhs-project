# D3-11 Zone 自动发现与传播报告（2026-09-18）

> 补齐多活系列最后一块：实例如何**自动知道自己在哪个 zone**（发现），以及 zone 如何**随调用链传播**（传播）——此前只有代码与单测，无独立报告。

## 一、实现

### 1.1 Zone 自动发现（ZoneLocator 组合）
- 接口 `ZoneLocator` + 三个实现，按 `getOrder` 组合（`CompositeZoneLocator`：取第一个成功定位的结果）：
  - `EnvVarZoneLocator`：环境变量 `MYXHS_ZONE`（显式优先）；
  - `FileZoneLocator`：zone 文件（容器/挂载场景）；
  - `IpRangeZoneLocator`：**网段映射**（如 `192.168.0.0/24=zone-a,10.0.0.0/8=zone-b`，支持 CIDR 前缀匹配）；
- `ZoneEnvironmentPostProcessor`：在**注册前**生效（Spring 环境后置处理），因此注册到 Nacos 的实例 metadata 自动携带 zone，无需人工标注。

### 1.2 Zone 传播（HTTP/Feign）
- `ZoneContextHolder`：请求级 ThreadLocal，头名 `X-Zone`（set/get/clear）；
- `ZonePropagationFilter`（入站，`OncePerRequestFilter`）：读上游 `X-Zone` → 写入 holder，**请求结束清理**（防线程池串味）；
- `ZonePropagationInterceptor`（出站，Feign `RequestInterceptor`）：为本机发起的调用附加 `X-Zone`（**优先请求级 holder，其次本机 zone**）；已存在头不覆盖、默认 zone 不附加；
- 可观测：`myxhs_zone_propagation_total{direction=in|out, zone=...}`（入站/出站分别计数）；
- 自动装配：`ZonePropagationAutoConfiguration`（条件开启）。

## 二、验证

| 项 | 结果 |
|---|---|
| 自动发现实测 | zone-a 由**网段**识别、zone-b 由**文件**识别（同一套代码不同环境） |
| 传播实测 | `X-Zone` 跨服务链路传播 **10/10**（调用链上各跳 zone 一致） |
| 单测 | `ZonePropagationTest` 3 项：入站 filter 写入并清理 holder / 出站 interceptor 加头 / 默认 zone 跳过；`ZoneLocatorTest` 4 项：文件定位 / 网段命中 / 网段未命中 / 组合顺序与回退 |

## 三、边界（诚实声明）
- **事件/MQ 传播未做**（仅 HTTP/Feign 链路）——MQ 消费侧 zone 上下文不自动携带；
- 无云元数据 locator（AWS EC2/ECS/Eureka 自动发现，本栈不需要，对比见 `docs/design/multi-active-vs-microsphere.md`）；
- 传播头**未做信任校验**（内部网络前提；网关已剥离外部伪造头，见 F-001 信任链）；
- 单机仿真环境，无跨机房传播时延验证。

## 四、交付物
- 代码：`my-xhs-common/.../zone/locator/{ZoneLocator,EnvVarZoneLocator,FileZoneLocator,IpRangeZoneLocator,CompositeZoneLocator,ZoneEnvironmentPostProcessor}.java`、`zone/propagation/{ZoneContextHolder,ZonePropagationFilter,ZonePropagationInterceptor,ZonePropagationAutoConfiguration}.java`
- 单测：`ZonePropagationTest`、`ZoneLocatorTest`
- 指标：`myxhs_zone_propagation_total`
- 关联：多活系列其余 11 份报告（见 `docs/interview/xhs/26-Zone多活与容灾.md` 证据索引）
