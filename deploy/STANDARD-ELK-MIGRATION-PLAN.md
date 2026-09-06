# 标准 ELK 迁移规划

> 2026-08-24
> 目标: 将当前 `应用直推 Logstash TCP` 的日志链路, 收敛为更标准的
> **应用落盘 JSON -> Filebeat/Agent -> Logstash -> Elasticsearch -> Kibana**。
> 范围: 先做深度规划, 不立即切断当前链路。

## 一、当前现状

当前系统不是“纯标准 ELK”, 而是**过渡态**:

1. **微服务侧**
   - 多数服务的 `logback-spring.xml` 同时具备:
     - 本地结构化 JSON 文件输出: `/logs/${APP_NAME}.json`
     - 远程 TCP 直推: `LogstashTcpSocketAppender -> 21.130.247.89:15044`
2. **中间件侧**
   - `logstash` 当前同时监听:
     - `15044/tcp` + `json_lines` (应用直推)
     - `15045/beats` (Filebeat/Beats 采集)
   - `kibana` + `elasticsearch` 已接入
3. **仓库里已有 filebeat 配置**
   - `config/filebeat/filebeat.yml` 存在
   - 但它当前描述的是“采容器 stdout”, 不是“采微服务 `/logs/*.json`”
4. **当前 deploy compose 中没有 filebeat 服务**
   - 所以运行态主要依赖应用直推 `15044`

结论:

- 这不是“从零开始上 Filebeat”
- 而是把现有**双路能力**收敛为**标准链路主、TCP 直推辅**

---

## 二、为什么要迁到标准 ELK

## 2.1 当前直推 TCP 的优点

1. 改动少
2. 不依赖额外采集器
3. 日志到 ES 的链路短
4. 对跨机部署很友好

## 2.2 当前直推 TCP 的问题

1. **应用与日志平台耦合过紧**
   - 应用必须知道 Logstash 地址和端口
2. **应用进程背负网络输出责任**
   - Logstash 抖动、网络抖动都直接影响应用 logging appender
3. **日志通道难统一治理**
   - 想加采样、过滤、缓冲、断点续传时, 采集器模式更自然
4. **不够标准**
   - 面试或生产经验表达时, 标准链路通常是“落盘 -> 采集 -> 处理 -> 存储”

## 2.3 标准 ELK 的收益

标准链路:

- 应用只负责写文件
- Filebeat/Agent 专职采集
- Logstash 专职解析
- ES 专职存储
- Kibana 专职查询

它的收益是:

1. 职责清晰
2. 更容易统一管理
3. 应用与日志平台解耦
4. 更贴近真实生产形态

---

## 三、当前策略是否仍然保留过渡期

当前已不再保留“长时间双轨并行”策略。

原因:

1. 微服务 `logback-spring.xml` 已从 root 主路径中移除了 `LOGSTASH` 直推
2. 标准 ELK 已明确收敛为唯一主链路
3. 继续长期保留 `15044` 只会让口径和运维复杂度再次回到过渡态

因此当前实施策略是:

### 阶段 A: 标准 ELK 主链路建起来
- Filebeat 加入 compose
- 让 Filebeat 开始采 `/logs/*.json`
- Logstash 保留 `15045` beats 输入
- Kibana/ES 验证 Filebeat 主链路可用

### 阶段 B: 验证主链路稳定
- 确认 `/logs/*.json -> Filebeat -> Logstash -> ES -> Kibana` 全链路真实通
- 确认字段完整(traceId/userId/spanId)

### 阶段 C: 清理旧口径
- 文档统一为“Filebeat 为唯一主链路”
- `15044` 不再作为默认生产路径描述

---

## 四、当前微服务改造影响面

## 4.1 好消息: 大部分服务已经具备 JSON 落盘能力

从 `logback-spring.xml` 看, 多个服务已有:

- `JSON_FILE` appender
- 路径统一为 `/logs/${APP_NAME}.json`
- `includeMdcKeyName` 已包含 `traceId` / `spanId` / `userId`

这意味着:

- **应用层不是从零改造**
- 标准 ELK 的关键前提已经具备了

## 4.2 仍然需要评估的代码/配置影响

### 需要改动的地方
1. **微服务 logback**
   - 需要决定 `LOGSTASH` appender 是否保留在 root 中
   - 需要决定是“默认禁用直推”还是“环境开关控制”
2. **启动脚本/运行环境**
   - 必须确保 `/logs` 在目标环境真实存在
   - 必须确保服务对 `/logs` 有写权限
3. **Filebeat 配置**
   - 从“采 Docker stdout”改成“采 `/logs/*.json`”
4. **Logstash 配置**
   - 保留 beats 输入
   - 视需要逐步弱化 `tcp 15044`

### 不一定要改的地方
1. 应用业务代码
2. TraceId 结构化字段
3. Elasticsearch/Kibana 基本拓扑

---

## 五、标准 ELK 的目标链路

## 5.1 当前目标生产链路

```text
微服务
  -> /logs/my-xhs-*.json
  -> Filebeat
  -> Logstash (beats 15045)
  -> Elasticsearch (myxhs-logs-YYYY.MM.DD)
  -> Kibana
```

## 5.2 当前结论

- **生产默认**: Filebeat 为唯一主链路
- **应用默认**: 不再直推 Logstash TCP
- **15044**: 不再作为默认生产路径描述

---

## 六、Filebeat 需要怎么改

## 6.1 当前 filebeat.yml 的问题

当前 `config/filebeat/filebeat.yml` 主要采 Docker 容器 stdout:

- `type: docker`
- `containers.ids: *`

这不等于采微服务 `/logs/*.json`。

## 6.2 目标 filebeat.yml 应该改成什么

应该新增/改成类似:

```yaml
filebeat.inputs:
  - type: filestream
    id: myxhs-json-logs
    enabled: true
    paths:
      - /logs/*.json
    parsers:
      - ndjson:
          overwrite_keys: true
          add_error_key: true

processors:
  - add_fields:
      target: ''
      fields:
        log_source: filebeat

output.logstash:
  hosts: ["127.0.0.1:15045"]
```

如果还想采中间件容器 stdout, 可以保留第二个 input, 但要分清来源。

---

## 七、Logstash 需要怎么改

## 7.1 当前状态

当前 Logstash 已支持:

- `tcp { port => 15044 codec => json_lines }`
- `beats { port => 15045 }`

## 7.2 当前建议

- `15045` 作为标准 Filebeat 主链路
- `15044` 即使暂时保留监听能力, 也不再作为默认生产路径
- 文档口径一律按 Filebeat 主链路表达

---

## 八、是否要改微服务代码

严格说:

- **不用改业务代码**
- 但 **要改日志配置**

这不是业务改造, 是运行时日志链路改造。

## 8.1 最小改动方案

### 方案 A: 不立刻删直推
- 保留 `LOGSTASH` appender
- 先加 Filebeat
- 双链路同时存在

优点:
- 风险最小
- 不会因为 Filebeat 配错直接丢日志

缺点:
- 有重复写 ES 的风险
- 需要在 Logstash / ES / Kibana 里区分来源

### 方案 B: 加环境开关控制
让 `LOGSTASH` appender 只在某个 profile 或环境变量开启。

例如:
- 本地开发: 开 TCP 直推
- 生产/准生产: 关 TCP, 只走 JSON_FILE + Filebeat

优点:
- 更干净
- 更符合最终目标

缺点:
- 需要统一改多份 logback

### 方案 C: 直接移除 LOGSTASH 主链路
- 只保留 `JSON_FILE`
- 全部交给 Filebeat

优点:
- 最标准
- 配置语义最清晰
- 一次性消除旧 IP `15044` 直推依赖

缺点:
- 需要先确认 Filebeat 主链路稳定

## 8.2 推荐

**当前已转向方案 C**:

- 标准 ELK 作为唯一主链路
- 微服务默认不再直推 Logstash TCP
- `LOGSTASH` 从 root 主路径中移除

---

## 九、迁移风险点

1. `/logs` 目录权限问题
2. Filebeat 配置不对导致日志不入 ES
3. 双链路同时开导致重复索引
4. Logstash pipeline 对 beats 输入和 TCP 输入的字段结构不一致
5. Kibana 查询时混入两种来源字段
6. 当前文档中“Filebeat 已部署”与实际 deploy compose 不一致, 需要统一口径

---

## 十、实施顺序建议

### Phase 1: 标准主链路已接入
1. `filebeat` 已加入 deploy compose
2. `config/filebeat/filebeat.yml` 已改为采 `/logs/*.json`
3. `LOGSTASH` 已从微服务 root 主路径中移除

### Phase 2: 验证主链路真实通
1. 启动最小微服务集合产生 `/logs/*.json`
2. 验证 Filebeat -> Logstash -> ES -> Kibana
3. 确认字段完整(traceId/userId/spanId)

### Phase 3: 文档与口径统一
1. 修正文档里“已完整 Filebeat”与“当前直推”之间的冲突
2. 明确标准 ELK 的当前唯一主链路模式

---

## 十一、我给你的建议

如果你已经决定“按标准 ELK 走”, 那最合理的策略不是立刻删除直推 TCP, 而是:

1. **先把 Filebeat 加回来**
2. **让它采 `/logs/*.json`**
3. **先验证标准链路通**
4. **再决定什么时候下掉 `LOGSTASH` appender**

这样既对标生产, 又不会把当前可用链路一下子打断。

## 最终结论

- **是的, 推荐走标准 ELK**
- **需要改配置, 也需要少量修改 logback 配置策略**
- **不建议一步硬切**
- **建议先并行、后收敛**
