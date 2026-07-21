分析 my-xhs 项目中以下 5 个高影响符号的影响面，逐一输出引用的文件数和主要调用方：
1. BizException — 用 rg 统计引用文件数
2. generateConversationId — 用 codegraph impact 分析影响面
3. resizeBuckets — 用 codegraph callers 查调用方
4. storeOfflineMessage — 用 codegraph callers 查调用方
5. publishNote — 用 codegraph callees 查依赖

每个符号输出格式：
- 符号名
- 引用文件数
- Top 5 调用方（文件名+行号）
- 风险等级（高/中/低）
