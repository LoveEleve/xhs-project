# my-xhs-search 架构分析 - ES三索引+Canal同步+热搜反作弊+ItemCF推荐
## 一、ES三索引
note_index(ik_smart) / product_index(status filter) / suggest_index(Completion Suggester FST)

## 二、数据同步链路
MySQL→Canal→MQ→ES(增量) + IndexRebuildJob MySQL全量→ES(凌晨4点兜底)

## 三、热搜算法
Lua反作弊: 用户+IP频率限制→分钟桶窗口→屏蔽词过滤→指数衰减ZSet排序

## 四、推荐流水线
5路召回(ItemCF/Content/Hot/Following/Geo) → 粗排(质量分) → 精排(点击率) → 重排(多样性)
