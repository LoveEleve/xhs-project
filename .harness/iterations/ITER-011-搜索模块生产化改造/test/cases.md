---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试用例: 搜索模块生产化改造

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | bulk 中某文档 version_conflict | 补偿批次 | 计为成功（不回填） | 是 |
| TC-2 | AC-1 | bulk 混合（1 冲突 + 1 真失败 + 1 成功） | 同上 | 仅真失败 id 回填；冲突/成功不回填 | 是 |
| TC-3 | AC-1 | 商品文档构建失败（确定性） | 同上 | 丢弃 + 告警（不回填，无循环） | 是 |
| TC-4 | AC-1 | 全冲突批次 | 同上 | 全部豁免 → 无回填 → 收敛 | 是 |
| TC-5 | AC-2 | note flat 消息（含 es 小整数 + binlogTimestamp） | 消费 | version=binlogTimestamp（ts 域）+ ExternalGte | 是 |
| TC-6 | AC-3 | 别名存在（note_index → note_index_v1） | resolveWriteIndex | 返回 note_index_v1 | 是 |
| TC-7 | AC-3 | legacy（note_index 为实体索引） | resolveWriteIndex | 返回 note_index + 回退标记 | 是 |
| TC-8 | AC-3 | 别名不存在 | ensure | 建 v1 + 别名指向 | 是 |
| TC-9 | AC-3 | v1 当前 | createNextIndex | 建 v2（mapping 复用） | 是 |
| TC-10 | AC-3 | 重建全流程（别名模式） | rebuild | 调用序：建 v2→bulk(v2)→清理(v2)→switch(v1→v2)→重放(alias)→删 v1 | 是 |
| TC-11 | AC-3 | 重建中途失败 | rebuild | 别名不动（仍 v1）；无删 v1 | 是 |
| TC-12 | AC-4 | note 物理删事件 | 消费 | 墓碑（status=-1 + deletedAt + ExternalGte 版本） | 是 |
| TC-13 | AC-4 | 超龄墓碑 | rebuild 清理 | delete_by_query（status=-1 && deletedAt<7d 前） | 是 |
| TC-14 | AC-5 | 5 组 DLQ 消息 | 重放 | 重投源 topic（tag 保留）+ attempt≤3 | 是 |
| TC-15 | AC-5 | product 消费异常后下一消息 | 消费 | ThreadLocal 无残留（finally remove） | 是 |
| TC-16 | AC-6 | — | 三控制器 | @RateLimit 落位；注释漂移 ×3 零残留 | 手工 |
| TC-17 | AC-7 | 全仓 | 十一模块重编测试 | 全绿 | 是 |
