# CT02: 批量查询 — POST /api/counter/batch-get
## § 源码分析
- **Controller**: `CounterController.java:57` → `@PostMapping("/batch-get")`
- **Service**: `CounterService.batchGetCounts()`: Redis Pipeline批量GET→未命中MySQL批量selectByTargets兜底
## § 业务逻辑
批量查多个对象计数→Pipeline一次RTT GET多key→未命中MySQL IN查询回填
## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 列表<100 | 参数校验 | 拒绝 |
## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| Redis | Pipeline SMEMBERS | 多个计数 |
## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Pipeline 1RTT | ✅ |
## § curl
```bash
curl -s -X POST http://localhost:19000/api/counter/batch-get -H "Content-Type: application/json" -d '[{"targetType":1,"targetId":123,"countType":1}]'
```
## § ASCII流转图
```
POST /counter/batch-get → Redis Pipeline MGET → 部分命中→MySQL IN查询补缺→合并返回
```
