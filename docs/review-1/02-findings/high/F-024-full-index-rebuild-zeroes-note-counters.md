# F-024 全量笔记索引重建会把 like/collect/comment 计数全部写成 0

## 严重度

High

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/job/IndexRebuildJob.java:325-337`
- `my-xhs-search/src/main/java/com/myxhs/search/job/IndexRebuildJob.java:171-186`

## 现象

全量笔记索引重建在构建 ES note 文档时，直接把：

- `likeCount = 0`
- `collectCount = 0`
- `commentCount = 0`

写入 ES，而不是从权威数据源读取真实计数。

## 证据

1. `buildNoteDocument()` 直接写死三个计数字段为 0：`IndexRebuildJob.java:332-334`。
2. 重建完成后会推进断点并标记 `status=COMPLETED`，视为成功完成：`IndexRebuildJob.java:171-186`、`:245-248`。
3. 这意味着一旦触发全量重建，ES 中所有笔记的计数都会被批量重置为 0，除非后续增量消费者再逐条修正。

## 触发条件

1. 定时全量重建（默认每天凌晨 4 点）
2. 或管理员手动触发全量重建

## 影响

1. ES 中 note 文档的 like/collect/comment 计数被系统性清零。
2. `sort=hot`、内容质量判断、任何依赖 ES 计数字段的查询都会退化。
3. 这不是单条补偿误差，而是全量重建带来的整体数据回退。

## 修复建议

1. 重建时从权威源读取真实计数，而不是写 0。
2. 若计数字段无法在重建时可靠获取，至少不要覆盖已有值；将计数留给专门的增量/计数消费者维护。
3. 手动/定时重建后补一轮计数字段修复，确保 ES 不长时间停留在全 0 状态。
4. 为重建任务增加“关键字段非零覆盖检查”。

## 是否需要补充验证

需要在有真实互动数据的环境触发一次 note 全量重建，确认重建后 ES note 文档计数字段是否被清零。