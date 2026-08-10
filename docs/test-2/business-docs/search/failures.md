# my-xhs-search 故障与陷阱
## 一、Canal同步延迟 — MySQL→ES数据不一致 应对: IndexRebuildJob凌晨4点全量兜底
## 二、热搜反作弊误伤 — 正常用户高频搜索被拦截 应对: Lua分钟桶+IP白名单
## 三、ItemCF冷启动 — 新笔记无相似度数据 应对: 降级hot+geo召回
## 四、ES不可用降级 — 搜索返回空 应对: fallback Redis缓存结果
## 五、搜索历史过多 — Redis List长度无限制 应对: Lua LPUSH+TRIM 最近20条
