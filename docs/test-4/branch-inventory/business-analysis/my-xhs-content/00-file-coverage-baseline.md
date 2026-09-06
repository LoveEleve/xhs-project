# my-xhs-content 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-content/inventory.md`

- `Dockerfile`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 38 个：
- 顶层构建文件：2 个
- `src/main/java`：30 个
- `src/main/resources`：4 个
- `src/test/java`：2 个

## 覆盖要求

- 必须逐个阅读 38 个候选文件
- 必须区分已读取、有源码行号证据、仅摘要、测试不可执行和暂不分析
- DTO、Entity、Enum、Mapper、配置和资源文件不能因文件短小而默认跳过
- `target/` 只作为构建产物记录，不作为当前源码事实
- 分析重点：内容发布、审核、评论、浏览/分享/删除事件、Feed 投递、Canal/ES 外部同步边界、Redis/MQ/本地消息、补偿任务、文件存储和敏感词过滤；点赞/收藏不属于本服务已实现能力
