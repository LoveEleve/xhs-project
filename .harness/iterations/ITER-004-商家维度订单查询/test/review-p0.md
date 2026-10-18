---
iteration: ITER-004
status: confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 用途：C-004 P0 深度代码审查（清单先行 → 逐项校验 → 回填结论）
---

# 深度审查: C-004 P0（商家维度订单查询·链路+列表）

## 审查范围（文件清单）

| # | 文件 | 类型 |
|---|------|------|
| 1 | `deploy/docker/my-xhs-deploy-zip/config/canal/conf/order_instance/instance.properties` | 新增·部署配置 |
| 2 | `deploy/docker/my-xhs-deploy-zip/docker-compose.yml`（canal 挂载行） | 修改·部署配置 |
| 3 | `my-xhs-search/.../config/IndexInitializer.java`（seller-order 索引） | 修改·索引定义 |
| 4 | `my-xhs-search/.../consumer/SellerSubOrderIndexSyncConsumer.java` | 新增·消费者 |
| 5 | `my-xhs-search/.../consumer/SellerSubOrderIndexWriter.java` | 新增·ES 写入器 |
| 6 | `my-xhs-search/.../service/SellerOrderSearchService.java` | 新增·查询服务 |
| 7 | `my-xhs-search/.../controller/SellerOrderSearchController.java` | 新增·控制器 |
| 8 | `my-xhs-search/.../dto/SellerOrderVO.java` / `SellerOrderPageVO.java` | 新增·DTO |
| 9 | `my-xhs-search/src/main/resources/application.yml`（seller-order 配置） | 修改·配置 |
| 10 | 测试 ×3（consumer/service/controller） | 新增·测试 |

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 部署配置（Canal + compose）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-01 | `filter.regex` properties 转义正确，全匹配 16 张物理表 | 模拟反转义 + 正则全匹配（脚本） | ✅ `my_xhs_order_\d+\.t_sub_order_\d+` 全匹配 16 表；`t_order_0` 正确排除 |
| R-02 | `slaveId=1004` 无冲突 | 全量核对 4 个 instance | ✅ 1001/1002/1003/1004 唯一 |
| R-03 | `partitionHash` 16 条覆盖全部物理表 | 脚本比对 filter 表集 vs hash 表集 | ✅ 16/16 覆盖，pk 均为 id（同单保序） |
| R-04 | compose 挂载 ↔ 文件实际路径 | 文件系统核验 | ✅ 挂载行（:490）↔ 文件存在（1572B） |
| R-05 | 配置项完整性（对比既有 instance） | diff 键集合 | ✅ 与 product_instance 键集合完全一致（无缺/无余） |

### B. ES 索引定义

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-06 | mapping JSON 语法合法 | python json 校验（%REPLICAS% 替换后） | ✅ 合法；3 shards；12 字段 |
| R-07 | 字段类型 ↔ 写入值逐字段匹配 | 逐字段对照 buildDoc | ✅ keyword×2/long×3/integer/scaled_float×3/boolean/date×2 全部匹配 |
| R-08 | 配置键链路一致（yml ↔ 三处 @Value） | 三处核对 | ✅ `search.seller-order.index-name:seller_sub_order_index` 三处一致 + yml 同键 |
| R-09 | `%REPLICAS%` 替换兼容 | 读替换逻辑 | ✅ 与既有索引同路径（全局替换） |

### C. 消费者

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-10 | canal 消息解析与既有消费者一致 | 对比解析段 | ✅ table/data/type/ts 解析 + 非识别格式跳过（与 Product/Note 同款） |
| R-11 | 表名过滤语义 | 枚举 16 表 + 反例 | ✅ `startsWith("t_sub_order")` 覆盖 16 表、正确排除 t_order/t_order_item |
| R-12 | 行字段 ↔ DDL 列名逐一核对（11 字段） | 对照 init-all.sql | ✅ 11 个映射列全部存在于 t_sub_order DDL（delivered_at 等未索引列不在映射内，符合设计） |
| R-13 | 事件语义（INSERT/UPDATE→upsert；DELETE/deleted=1→softDelete） | 读码 + 测试 | ✅ 四类路径均有测试覆盖 |
| R-14 | 版本机制（ts 毫秒 + ExternalGte）与 T-088 同族风险 | 对照 Product 的 T-088 修复注释 | ✅ ts-first 同款（规避 es 小整数回退冻结）；📝 P1 回填版本源必须用毫秒量级（updated_at）保持一致 |
| R-15 | 幂等/乱序论证 | 推演 + ExternalGte 语义 | ✅ doc id=subOrderNo 重放幂等；并发消费乱序由版本兜底；📝 同毫秒同行事件等版本→后处理者胜（边缘可接受） |
| R-16 | 失败补偿键与既有模式一致 | 对比 Product | ✅ `myxhs:es:sync:failed:seller-sub-order`（同族命名）+ TTL 1h + 上抛重试 |
| R-17 | 多行批次中途失败补偿覆盖 | 推演 multi-row 场景 | 🟡 **发现并修复**：原实现只记录当前失败行，批次内后续未处理行在"重试仍失败进 DLQ"时丢失 → 改为记录"当前失败行 + 未处理行"（`recordFailedBatch`）+ 新增测试 |
| R-18 | 非法消息不误重试 | 测试覆盖核对 | ✅ 空体/缺 table/data/空 data/非子单表 → 跳过；非 JSON → 重试（防御性，可接受） |
| R-19 | trace 还原 | 对比既有消费者 | ✅ `MqTraceHelper.restoreTraceId(msg)` |

### D. ES 写入器

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-20 | 版本冲突判定与既有实现等价 | 对比 Product 私有实现 | 🟡 **发现并修复**：原实现仅查 `e.getMessage()` 单字符串——改为与既有完全一致的 **cause 链遍历 + "version_conflict"/"version conflict" 双写法**（否则包装异常会导致误重试） |
| R-21 | 软删除文档语义（整体替换 → 仅 subOrderNo+deleted） | 推演 + mapping 对照 | ✅ 列表 `deleted=false` 过滤生效；版本防乱序覆盖；与 Product 软删（status=-1）同思路 |
| R-22 | 异常处理（IOException/RuntimeException 重抛；冲突吞掉） | 读码 | ✅ |

### E. 查询服务

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-23 | 查询构造：sellerId + deleted=false 必带 | 读码 + 测试 | ✅ 基础用例断言 2 个必带过滤 |
| R-24 | 可选条件/排序/分页 | 读码 + 测试 | ✅ status term + createdAt range + 倒序 + from/size（全条件用例断言 4 过滤） |
| R-25 | 参数兜底（page<1→1；size<1→默认；size>max→截断） | 测试 | ✅ |
| R-26 | 结果映射 toVO | 测试 | ✅ 金额 BigDecimal/状态 Number/时间 String |
| R-27 | ES 异常 → BizException 降级 | 测试 | ✅ |
| R-28 | 深分页边界（from+size 限制）已记录 | 注释核验 | ✅ javadoc 记录（search_after 为演进） |
| R-29 | 服务层 sellerId 纵深防御 | 读码评估 | 🟡 **发现并修复**：原服务层直呼将 NPE（且注释误导）→ 增加服务层守卫（BizException 40002）+ 测试 + 注释修正 |

### F. 控制器

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-30 | 守卫（403 / 40002 / sellerId=0 合法） | 测试 | ✅ 5 用例 |
| R-31 | 参数透传与响应结构 | 测试 | ✅ |
| R-32 | 网关鉴权链 | 网关配置核验 | ✅ `/api/search/**` 已路由；新路径**不在任何白名单** → JWT 必需 + X-Admin-Call；HMAC 当前全局关闭（hmac-enabled: false） |

### G. 正确性守卫（AC-6）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-33 | 守卫注释落位 | 代码核验 | 🟡 **补齐**：消费者/服务/控制器已有；**写入器 javadoc 缺失 → 已补**（4 处齐全） |

### H. 测试与回归

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-34 | 新增用例覆盖 AC-2/3/4/5（TC-2~TC-10） | 用例↔AC 对照 | ✅ 消费者 9 / 服务 8 / 控制器 5 |
| R-35 | 全量回归全绿 | mvn test | ✅ 六模块 + search（详见报告回填） |
| R-36 | 测试盲点 | 用例集评估 | 🟡 多行批次原缺 → **已补 2 用例**；📝 ts 缺失回退（ts-first 逻辑与既有同款，靠代码评审覆盖）；📝 物理 DELETE 依赖行镜像含 sub_order_no（binlog_row_image=FULL；子单无物理删除路径 → 非实际风险，记录） |

### I. 模式一致性

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-37 | 与 ProductIndexSyncConsumer 差异清单 | 逐段对比 | ✅ 有意差异：无 Feign 富化（文档=单表行）、无 flat 格式（无该生产者）、表过滤=前缀（16 分片）；**意外差异（isVersionConflict）已修复** |
| R-38 | 命名一致性 | 全链路核对 | ✅ topic/group/index/redis key 四处一致 |

### J. 需求对照（P0 范围）

| # | 检查项 | 方法 | 结论 |
|---|--------|------|------|
| R-39 | REQ-1/2/3/6 的 P0 部分逐条核对 | 对照 prd | ✅ AC-1~6 全部落实（AC-9 回归在本轮） |
| R-40 | 无 scope creep（未提前做 P1） | 变更文件核对 | ✅ order 域零改动；无详情/扫描/回填 |

## 校验执行记录

| 项 | 命令/证据 | 结果 |
|----|----------|------|
| R-01/R-03 | python 模拟 properties 反转义 + 正则/集合比对 | 16/16 全匹配、16/16 hash 覆盖 |
| R-06 | python json.loads（mapping 常量） | 合法、12 字段 |
| R-12 | `sql/init-all.sql` t_sub_order DDL 对照 | 11/11 列存在 |
| R-20 | 对照 ProductIndexSyncConsumer#isVersionConflict | 已对齐（cause 链 + 双写法） |
| 修复后回归 | `mvn -o -pl my-xhs-search -am test` | search 28 全绿（+3 新用例） |
| 全量回归 | 六模块 + search | 见测试报告 |

## 总结

- 🔴 严重问题：**0**
- 🟡 发现并修复：**4**（R-17 批次补偿覆盖 / R-20 版本冲突判定 / R-29 服务层纵深 / R-33 写入器守卫注释）
- 📝 记录（边界，不阻塞）：物理 DELETE 行镜像依赖、ts 缺失回退、同毫秒等版本、track_total_hits 默认上限、深分页 search_after 演进、P1 回填版本源须毫秒量级
- 🟢 通过项：其余 30 项
- **结论：P0 代码可进入收口（回归通过后提交）；P1 开工前先读本文件"记录"区（回填版本源约束）**
