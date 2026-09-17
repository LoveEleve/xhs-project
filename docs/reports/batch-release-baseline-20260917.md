# 批量发布与容量新基线（2026-09-17，A2 修复全量生效）

## 一、批量发布结果
15 个服务全部重建（clean package）并经发布通道（release-service.sh）发布成功，健康检查 15/15=200。
- 携带修复：common 的 TraceContextHolder 静态桥接（消除每请求 Class.forName 类加载锁）
- 发布记录：product/home/cart（19:22-19:47）+ user/content/analytics/counter（20:00）+ coupon/inventory/order/payment（20:00）+ notification/im/search/gateway（20:01）
- 网关发布后 Nacos 规则正常到达（突发 200 → 50×200 + 150×429 ✅）

## 二、容量新基线（ab 20000/c=64；wrk 15s，本地单机）

| 接口 | 修复前 RPS | **修复后 RPS** | 提升 | P99(新) | 备注 |
|---|---:|---:|---:|---:|---|
| product 详情 | 1,074 | **27,442** | 25.5x | 19ms | L1+类加载修复 |
| user info | 958 | **5,846** | 6.1x | 40ms | |
| note 详情 | 1,048 | **4,171** | 4.0x | 71ms | |
| comment 列表 | 1,064 | **2,469** | 2.3x | 96ms | DB 占比高（下一调优点） |
| gateway→note 详情（放行后） | 1,058 | **4,640** | 4.4x | 27ms | 代理≈0（>直连） |
| home 聚合 | 430 | **552** | +28% | 433ms | 聚合受限；P99 略超 SLO 400ms（观察项） |
| search（8 命中） | ~392-575 | **~500** | ~持平 | 192ms | **ES-bound**（0 Non-2xx；ab 失败全为长度不匹配） |

## 三、结论与遗留
1. 平台原真实吞吐被类加载锁压在 ~1k RPS；修复后 **product 达 2.7 万 RPS**，全链路为原基线的 2-25 倍；
2. 新的瓶颈层浮出：**search（ES）~500 RPS**、**comment（DB）~2.5k RPS**、**home 聚合 P99 433ms**；
3. A1 报告中的所有数字（除 search 外）已作废，以本报告为准；SLO 基线同步更新；
4. 待办：搜索的 ES 调优（分片/refresh/查询精简）、comment 的 DB 索引/JOIN 优化、home 聚合超时与扇出收敛。

## 四、口径更新索引
- A1 容量报告：`docs/reports/capacity-20260917.md`（数字已过时，保留作"修复前下界"）
- A2 调优报告：`docs/reports/a2-jvm-tuning-20260917.md`（修复细节）
- SLO：`docs/slo/slo-and-degradation-matrix.md`（基线更新为本报告）
