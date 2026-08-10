# 26 全链路真实验证

> 复审维度 26 | 元层级 | 逐端点逐层验证——HTTP/MQ/Redis/MySQL/XXL-Job/Logs/Trace/Metrics
>
> 你是谁：分布式系统测试员。**不只看 200**——每一层的数据都必须符合预期。
> 每个端点单独测试，每次只测一个端点，禁止批量脚本。

---

## 验证规则

### 26.1 五层验证模型

每个端点测试必须验证以下五层，缺一不可：

| 层 | 验证内容 | 验证方法 |
|------|------|------|
| HTTP | 状态码 + 响应体字段正确性 | curl + jq 字段比对 |
| MySQL | 行增/改/删正确 + 字段值正确 | mysql 直连查询 |
| Redis | key 存在/不存在 + value 正确 + TTL | redis-cli GET/HGET/ZRANGE |
| MQ | topic/tag 正确 + Consumer 实际消费 | RocketMQ Console + Consumer 日志 |
| Trace | traceId 全链路串联 | SkyWalking/Grafana + Kibana 日志检索 |

### 26.2 预状态快照

测试前必须记录：

```
- MySQL: SELECT * FROM t_xxx WHERE ...
- Redis: GET/HGET key
- MQ: 确认 Consumer 在运行
- Trace: 确认 SkyWalking agent 已加载
```

### 26.3 每端点输出格式

```
## 模块：XX | 端点：POST /api/xxx/yyy
### 测试参数
### 预状态
### 执行
curl -X POST ...
### 验证
| 层 | 预期 | 实际 | 结果 |
| HTTP | 200 + data.xxx=yyy | ... | ✅/❌ |
| MySQL | t_xxx 新增 1 行 | ... | ✅/❌ |
| Redis | key 存在/value=zzz | ... | ✅/❌ |
| MQ | topic=XXX/Consumer 日志见 | ... | ✅/❌ |
| Trace | traceId 从 gateway→XXX→XXX | ... | ✅/❌ |
```

---

## 验证命令

```bash
# HTTP
curl -s -w "\n%{http_code}" http://...

# MySQL
mysql -h127.0.0.1 -uroot -pXhs@2026#MySQL my_xhs_xxx -e "SELECT ..."

# Redis
redis-cli -a Xhs@2026#Redis GET "key"

# Consumer 日志
tail -100 /var/log/my-xhs/xxx.log | grep -E "消费|consume|onMessage"

# Trace (SkyWalking)
curl -s http://localhost:12800/graphql -X POST -d '{"query":"..."}'

# Kibana 日志
curl -s "http://localhost:15601/..." -H 'kbn-xsrf: true'
```
