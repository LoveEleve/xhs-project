# my-xhs-product 已知故障与陷阱

## 一、测试陷阱

### 1. 布隆过滤器误判
- **现象**: 不存在的SPU ID有时返回旧缓存值
- **根因**: 布隆过滤器判"可能存在"但实际不存在(误判率~1%)
- **验证**: 布隆命中但Redis/MySQL都没数据 → 空值缓存2min → 2min后重新查

### 2. 缓存逻辑过期被返回旧数据
- **现象**: SPU更新后查询仍返回旧值
- **根因**: 逻辑过期(30min)后返回旧值+异步重建, 重建完成前看到旧数据
- **应对**: 写操作(UPDATE)主动删除Redis key, 下次读触发CacheAside

### 3. 创建SPU需要Admin Token
- **现象**: P01/P02/P05/P06返回403
- **根因**: `X-Admin-Call` header校验, adminToken必须匹配
- **应对**: 请求带 `X-Admin-Call: my-xhs-admin-token-2026`

### 4. 批量查SKU需要Internal Token
- **现象**: P08返回403
- **根因**: `X-Internal-Call` header校验
- **应对**: 内部调用才可用, 外部测试跳过此端点或通过下单链路自动触发

### 5. Canal→ES同步延迟
- **现象**: 创建SPU后立即搜索查不到
- **根因**: MySQL→Canal→MQ→ES pipeline有延迟(正常<1s)
- **应对**: 创建后等2-3秒再搜索

## 二、依赖故障

### 6. Redis不可用
- **现象**: GET /spu/{id} 变慢(跳过Redis直查MySQL)
- **根因**: 布隆过滤器和缓存都依赖Redis
- **验证**: Redis ping检查

### 7. ES不可用
- **现象**: /api/search/product 返回500
- **影响**: 仅搜索功能, SPU/SKU增删改查不受影响

## 五、代码级缺陷（已修复+待修复）

### 8. 删缓存与异步重建竞态（已修复）
- **现象**: SPU 更新后最长 30min 不生效
- **根因**: afterCommit 删缓存后，并发读的异步重建可能用旧 DB 数据回填 Redis，覆盖了删除操作
- **修复**: 延迟双删 — afterCommit 立即删 + 1s 后二次删（SpuService.java:332-340）

### 9. Redis 启动期不可用导致无法启动（已修复）
- **现象**: Redis 未就绪时 product 服务启动失败
- **根因**: `initBloomFilter()` 中 `tryInit()` 无 try-catch，Redis 连接失败抛异常中断启动
- **修复**: 包 try-catch，失败时 `bloomFilterReady=false` 降级启动（SpuService.java:138-165）

### 10. Canal flatMessage 不兼容导致 ES 永不同步（待修复）
- **现象**: SPU 创建/更新后搜索永远查不到
- **根因**: `canal.properties` 设 `flatMessage=true`，但 Consumer 按数组解析 → ClassCastException → 静默丢弃
- **修复**: 改 `flatMessage=false` 或重写 Consumer 兼容 flatMessage 格式

### 11. 创建 SPU 无幂等（待修复）
- **现象**: 管理端重复点击产生重复商品
- **根因**: `createSpu` 无 `@Idempotent`、无唯一约束
- **修复**: 加 `@Idempotent` 或 DB UNIQUE(name, category_id)

### 12. SPU 创建默认直接上架无审核
- **现象**: 创建即 status=1 上架
- **根因**: 设计如此 — 无 `audit_status` 字段
- **应对**: 如需审核改为默认 OFF_SHELF(0)

### 13. SKU 详情 vs 批量查询状态过滤不一致
- **现象**: `getSkuDetail` 返回下架 SKU，`batchGetSkuDetails` 不返回
- **根因**: 两接口 status 过滤逻辑不同
- **应对**: 统一语义 — 批量接口也返回下架 SKU 并带 status 字段
