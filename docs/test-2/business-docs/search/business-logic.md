# my-xhs-search 业务逻辑 - ES搜索+lu​a反作弊+ItemCF+5路召回
## 一、ES搜索 — note_index ik_smart分词 multi_match title^3+content + filter + sort + SearchAfter分页
## 二、热搜 — Lua反作弊(用户+IP频率+分钟窗口+屏蔽词)→ZSET zrevrangebyscore + Set pinned置顶  
## 三、推荐 — 5路召回(ItemCF/Content/Hot/Following/Geo)→粗排精排重排→冷启动降级hot+geo
## 四、Canal同步 — MySQL t_note/t_spu → Canal → MQ → ES增量 + IndexRebuildJob凌晨4点全量兜底
