# CT01: 计数查询 — GET /api/counter/get
## § 源码分析
- **Controller**: `CounterController.java:40` → `@GetMapping("/get")`, @RateLimit
- **Service**: `CounterService.getCount()`: Redis GET myxhs:counter:{type}:{id}:{countType}→命中返回→未命中MySQL SELECT t_counter回填
## § 业务逻辑
查点赞/收藏/评论计数→Redis命中直接返回→未命中MySQL兜底
## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 计数写入 | SOCIAL_TOPIC消费正常 | 返回旧值 |
## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| Redis | `r.get('myxhs:counter:1:123:1')` | 点赞数 |
| MySQL | `SELECT count FROM t_counter WHERE type=1 AND id=123` | 一致 |
## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Redis O(1) | ✅ |
## § curl
```bash
curl "http://localhost:19000/api/counter/get?targetType=1&targetId=2085989641275572226&countType=1"
```
## § ASCII流转图
```
GET /counter/get → Redis GET → 命中→返回 / 未命中→MySQL→回填Redis
```
