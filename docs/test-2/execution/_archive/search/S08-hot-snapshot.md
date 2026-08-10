# S08 — 热搜快照 (GET /api/search/hot/snapshot)

> 2026-08-08 | 阶段14-3 修复后重测 | search服务 | mytestuser

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (/api/search/hot/snapshot?date=2026-08-08)
         → HotSearchService.getHotSearchSnapshot()
           → MySQL:13307 → my_xhs_content.t_hot_search_snapshot
             (WHERE snapshot_time BETWEEN date AND date+1day ORDER BY DESC LIMIT 50)
```

## 业务逻辑

按日期查询历史热搜快照。快照由 @Scheduled(60s) 定时任务从 Redis realtime ZSet 聚合后批量持久化到 MySQL。返回该日期所有快照（按时间倒序），每条含 rank/keyword/score。

## 七层验证（修复后重测）

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id: 8d6ee7e3..., 42条快照 |
| MySQL | ✅ | t_hot_search_snapshot 42行, 7词×6次快照 |
| Redis | ✅ | realtime ZSet 数据源正常 |
| MQ | N/A | — |
| SW | ✅ | traceId captured |
| Prometheus | ✅ | search actuator: /api/search/hot/snapshot 2条 |
| Kibana | ✅ | traceId=8d6ee7e... 4条日志(Gateway+search) |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -G "http://localhost:19000/api/search/hot/snapshot" \
  --data-urlencode "date=2026-08-08" \
  -H "Authorization: Bearer $TOKEN"
```

## 修复记录

**问题**: t_hot_search_snapshot 表不存在，快照功能降级返回空数组。
**修复**: 在 my_xhs_content 库创建表 (id/keyword/score/rank_no/search_count/snapshot_time)。
**影响定时任务**: HotSearchService @Scheduled 从 5min → 1min，加速验证。

### 最终全链路验证 (2026-08-08 11:56-11:58)

| 步骤 | 时间 | 结果 |
|------|------|------|
| S05 记录"卫衣/美妆" | 11:56:46 | 200 OK, Redis窗口={卫衣:1,美妆:1} |
| @Scheduled计算 | 11:57:00 | "Top 9 词, 最高分=0.90" (60s间隔确认) |
| MySQL快照 | 11:57:01 | 卫衣 rank#1(0.90), 美妆 rank#2(0.90) |
| @Scheduled计算 | 11:58:01 | "Top 9 词" (再次60s间隔确认) |
| MySQL快照 | 11:58:01 | 卫衣 rank#1(0.82), 美妆 rank#2(0.82) (衰减正常) |
| S09 热搜榜 | 11:58 | 11项(3置顶+8热词), 新词即时入榜 |

**结论**: 建表 → @Scheduled(60s) → 全链路数据流完整正确, 无降级。
