# 测试执行模板 — 方法论 L1→L4

> 每端点一文件，严格遵循分层验证模型。禁止越层。

---

## L0: 前置检查

在业务逻辑验证前，必须先确认**环境正确性**：

| 检查项 | 命令 | 预期 |
|------|------|------|
| Token 有效 | `cat /tmp/test_token.txt` | 非空 |
| 前置数据存在 | 见具体端点 | — |
| 依赖服务在线 | Nacos 查询 | UP |

---

## L1: 业务逻辑正确性

### 1.1 正常路径

```bash
# (贴完整 curl 命令，含所有 Required Header)
curl -s -i http://localhost:19000/api/*** \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ *** }'
```

**HTTP 响应**:
```
(贴完整响应: 状态码 + X-Trace-Id + body)
```

**响应解读**:
- 状态码: `XXX` ✅ / ❌ (预期 200)
- TraceId: `xxx-xxx-xxx` (SkyWalking 可追踪)
- 关键字段: (列 3-5 个业务关键字段 + 值)

### 1.2 异常路径

| 场景 | curl | 状态码 | 响应关键字段 | 通过 |
|------|------|:--:|------|:--:|
| 未认证(无JWT) | `curl http://localhost:19000/api/***` | 401 | `"msg":"未认证"` | |
| Token过期 | 用已过期 Token | 401 | `"msg":"Token已过期"` | |
| 参数缺失 | 不传必填字段 | 400 | `"msg":"参数校验失败"` | |
| 业务规则违反 | 见具体端点 | 4xx | 对应错误信息 | |

### 1.3 业务逻辑验证

| 验证点 | 说明 |
|------|------|
| 状态流转是否正确 | 如: 订单创建→status=0 |
| 返回值是否符合业务约定 | 如: accessToken 长度>100 |
| 幂等性 | 重复请求返回相同结果 |

---

## L2: 数据正确性（八层）

| 层 | 验证命令 | 预期值 | 实际值 | 通过 |
|------|------|------|------|:--:|
| HTTP | (贴 curl 命令) | — | — | — |
| Redis | `python3 -c "...r.get('key')"` | 预期值 | (贴返回值) | |
| MySQL | `mysql -e "SELECT ..."` | 预期行数/字段值 | (贴输出) | |
| MQ | `grep topic /tmp/r_{service}.log` | "消费成功" | (贴日志行) | |

---

## L3: 生产级质量（九透镜）

| # | 透镜 | 检查项 | 通过 | 证据 |
|:--:|------|------|:--:|------|
| 1 | 性能 | RT < 500ms? @RateLimit 生效? | | |
| 2 | 可扩展 | 分片键? 无状态? | | |
| 3 | 微服务 | Feign 超时? 降级? 熔断? | | |
| 4 | 分布式 | 数据源正确(主/从/分片)? | | |
| 5 | 并发 | 锁粒度? 幂等? Lua原子? | | |
| 6 | 安全 | JWT? Admin? HMAC? 脱敏? | | |
| 7 | 弹性 | 降级? 重试? 超时? | | |
| 8 | 可观测 | TraceId? 指标? 日志? | | |
| 9 | 一致性 | 主从延迟? 缓存一致? | | |

---

## L4: 链级可观测性（每链完成后汇总）

在各端点全部完成后，对整条链做系统级验证：

```bash
# Prometheus 快照
curl -s "http://21.130.247.89:19090/api/v1/query?query=http_server_requests_seconds_count{job='my-xhs-user'}" | python3 -c "..."

# MQ 积压检查
RocketMQ Dashboard → CART_TOPIC / ORDER_TRANSACTION_TOPIC diff

# SkyWalking 验证
通过 TraceId 追踪全链路调用

# Kibana 验证
curl -s "http://21.130.247.89:9200/myxhs-logs-*/_search?q=traceId:xxx" | python3 -c "..."
```

---

## 发现的问题

| # | 严重程度 | 描述 | 根因 | 修复状态 |
|:--:|:--:|------|------|:--:|
| 1 | | | | |
