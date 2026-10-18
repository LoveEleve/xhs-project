---
iteration: ITER-004
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 商家维度订单查询（异构索引）

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | deploy zip | 核验 order_instance 配置 | filter=16 表正则/slaveId=1004/topic/partitionHash 16 条 + compose 挂载 | 手工 |
| TC-2 | AC-2 | canal INSERT 消息 | 消费者 onMessage | ES upsert 调用（doc id=sub_order_no，字段映射正确） | 是 |
| TC-3 | AC-2 | canal UPDATE 消息 | 同上 | ES upsert（幂等重放：重复消息不产生重复文档——同 id 覆盖） | 是 |
| TC-4 | AC-2 | canal DELETE 消息 / deleted=1 | 同上 | ES delete 调用 | 是 |
| TC-5 | AC-2 | 非法消息（空体/无 data/无法识别） | 同上 | 跳过 + 不重试（正常返回） | 是 |
| TC-6 | AC-3 | ES 客户端抛异常 | 同上 | Redis Set `myxhs:es:sync:failed:seller-sub-order` + 异常上抛（触发重试） | 是 |
| TC-7 | AC-4 | — | GET list 缺 sellerId | 400（防全量泄漏） | 是 |
| TC-8 | AC-4 | — | GET list 非 X-Admin-Call | 403 | 是 |
| TC-9 | AC-5 | sellerId 合法 | GET list（status/时间范围/分页） | ES 查询构造含 term sellerId + term status + range createdAt + sort desc + from/size | 是 |
| TC-10 | AC-5 | 同上 | GET list 默认分页 | from=0/size=默认（20） | 是 |
| TC-11 | AC-6 | — | 核验消费者/API 注释 | 含"ES 只做发现；写操作/终态以 DB+CAS 为准" | 手工 |
| TC-12 | AC-7 | 子单存在（映射存在） | GET detail | 解析 orderNo→映射→userId→selectBySubOrderNo 调用 + 返回子单 | 是 |
| TC-13 | AC-7 | 映射缺失 | GET detail | 明确错误（不静默、不 scatter） | 是 |
| TC-14 | AC-8 | 回填 Job ×2（重入） | 执行 | ES bulk 幂等（同 id 覆盖；重复执行无重复文档） | 是 |
| TC-15 | AC-8 | 扫描分页（多页） | 执行 | 游标推进直至耗尽（覆盖全部物理表） | 是 |
| TC-16 | AC-9 | 全仓 | 全量重编测试 | 六模块 + search 基线全绿 + 新增用例通过 | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-17 | AC-5 | sellerId=0（平台自营） | 合法查询（正常返回） |
| TC-18 | AC-5 | 深分页（from>10000） | ES 默认限制：记录为演进（search_after）；当前返回空/报错按 ES 行为 |
| TC-19 | AC-3 | ES 不可用（列表查询） | 列表报错降级；写路径不受影响（架构性保证，记录） |
| TC-20 | AC-2 | 消息含物理表名 t_sub_order_1 | 正常处理（表名不参与映射） |
