# 11 注册中心

> 复审维度 11 | 每个模块必查 | 9 透镜全覆盖，Nacos 配置/注册/发现的正确性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[11] 11 注册中心：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [11]）。**
## 检查项

### 11.1 服务注册正确性 | 透镜：微服务/工程

**必须检查**：模块是否在 Nacos 正确注册——服务名、IP、端口、Group 是否与 Feign 调用方一致。

**怎么查**：
```bash
grep -rn 'spring.application.name\|spring.cloud.nacos.discovery.server-addr\|nacos.discovery.group\|nacos.discovery.ip' my-xhs-<module>/src/main/resources/
# 检查 Nacos 实际注册状态（仅本地环境可用）
curl -s http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=my-xhs-<module> 2>/dev/null | python3 -m json.tool 2>/dev/null
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 服务名不一致 | yml 写 `my-xhs-order` / Feign 调用写 `myxhs-order`→发现不到 |
| Group 不一致 | 注册在 `DEFAULT_GROUP` / 发现到 `dev`→发现不到 |
| 多网卡未指定 IP | Nacos 注册了 docker0 的 IP→其他服务连不上 |
| 临时实例 vs 持久实例 | 用持久实例→服务下线后 Nacos 不剔除→Feign 调死节点 503 |

**案例**：（全特性面预置检查项——my-xhs Feign 调用通过 Nacos 服务发现，IP 配置为 `spring.cloud.nacos.discovery.ip` 需逐模块验证。）

---

### 11.2 假 Nacos 迁移 | 透镜：盲区/工程

**必须检查**：yml 注释声称"已迁移至 Nacos"但实际配置仍在本地 yml 文件中——需要逐项 Nacos 查询验证。

**怎么查**：
```bash
grep -rn '迁移至 Nacos\|迁移到 nacos\|moved to nacos' my-xhs-<module>/src/main/resources/
# 每处声称迁移的配置，在 Nacos 中验证是否存在（仅本地环境可用）
curl -s http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=my-xhs-<module>.yml 2>/dev/null
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| yml 注释说"已迁移到 Nacos"但 Nacos 查不到 | 注释说谎→AI/其他开发者被误导 |
| 部分配置在 Nacos 部分在 yml | 修改时不确定在哪改→双份配置可能不一致 |
| shared-configs 未配置 | `shared-configs` 没用或指向不存在的配置→Nacos 配置未加载 |

**案例**：my-xhs 14/14 模块的 yml 注释声称"已迁移至 Nacos"但 Nacos 实际查询全空——全量假迁移（已修复：逐模块在 Nacos 中创建配置 + 排假注释）。

---

### 11.3 健康检查配置 | 透镜：生产级/微服务

**必须检查**：Nacos 健康检查是否启用（非默认心跳）、健康检查间隔和超时是否合理。

**怎么查**：
```bash
grep -rn 'health-check\|health-check-enabled\|check-rt\|heart-beat-interval' my-xhs-<module>/src/main/resources/
```

**判定**：
- 只用默认心跳（TCP 连接检查）→服务假死（端口在进程 segfault）→仍被路由→调假死服务 500
- 健康检查间隔 30s→服务假死后 30s 内全部请求 500→应 ≤5s
- 元数据 `preserved.heart.beat.timeout=15`→15s 无心跳就下线→太短可能误下线正常服务

**案例**：（全特性面预置检查项——my-xhs 当前用 Nacos 默认配置，建议逐模块评估健康检查间隔 + 加 Spring Actuator health 端点 Nacos 元数据。）

---

### 11.4 配置中心一致性 | 透镜：分布式/工程

**必须检查**：模块的本地 yml 和 Nacos 配置是否一致——有没有 Nacos 配置已被删除但 yml 仍引用、或不一致的覆盖关系。

**怎么查**：
```bash
grep -rn 'spring.cloud.nacos.config\|nacos.config\|shared-configs\|extension-configs' my-xhs-<module>/src/main/resources/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| Nacos 配置被删但 yml 仍引用 | 启动时 Nacos 报 timeout→要么拉不到配置启动失败、要么用本地 fallback→不一致 |
| shared-configs 引用不存在 | 依赖公共配置文件但被清理→启动失败 |
| 不同环境用同一 Nacos namespace | dev/prod 共享配置→修改 dev 影响 prod 或反过来 |

**案例**：（全特性面预置检查项——my-xhs 的 Nacos 配置迁移后，shared-configs 如 `my-xhs-common.yml` 需验证在 Nacos 中存在。）

---

### 11.5 注册中心耦合 | 透镜：可扩展性

**必须检查**：模块是否直接绑定 Nacos API（`NacosDiscoveryProperties`/`NamingService`）还是仅通过 Spring Cloud Commons（`DiscoveryClient`）。切 Consul/Eureka 的成本。

**怎么查**：
```bash
grep -rn 'NamingService\|NacosDiscoveryProperties\|com.alibaba.nacos' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'DiscoveryClient\|ServiceInstance\|com.netflix\|Consul' my-xhs-<module>/src/main/java/ | wc -l
```

**判定**：前者 >0→直接绑定 Nacos API→切注册中心需改代码；只用 `DiscoveryClient`→切换只改依赖 + yml。**记录即可，不强制修改。**

**案例**：（全特性面预置检查项——my-xhs 是否直接使用 Nacos API 需逐模块 grep 确认。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| Feign 服务发现 | 08.3 | FeignClient name 与 Nacos serviceName 一致性 |
| 健康检查端点 | 10.3 | /actuator/health 组件检查 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# 服务名/注册配置
grep -rn 'application.name\|discovery.server-addr\|discovery.group\|discovery.ip' my-xhs-<module>/src/main/resources/

# 假迁移声明
grep -rn '迁移至 Nacos\|迁移到 nacos\|moved to nacos' my-xhs-<module>/src/main/resources/

# 健康检查
grep -rn 'health-check\|check-rt\|heart-beat-interval\|heart-beat-timeout' my-xhs-<module>/src/main/resources/

# 配置中心引用
grep -rn 'nacos.config\|shared-configs\|extension-configs' my-xhs-<module>/src/main/resources/

# Nacos API 绑定
grep -rn 'NamingService\|NacosDiscoveryProperties\|com.alibaba.nacos' my-xhs-<module>/src/main/java/ | wc -l
```
