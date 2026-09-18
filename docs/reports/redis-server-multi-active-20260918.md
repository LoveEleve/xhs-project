# D3-8 Redis Server 多活（双主仿真）报告（2026-09-18）

## 一、目标与拓扑
- 目标：补齐 Redis **服务端**多活——双可写主库 + 跨 Zone 双向同步 + 冲突解决 + 单侧故障不中断。
- 拓扑（单机仿真）：
  - zone-a 主库：6379（既有 `my-xhs-redis`，`notify-keyspace-events KEA`）
  - zone-b 主库：**6381**（新增 `my-xhs-redis-zone-b`，独立可写，AOF everysec）
  - 同步 Worker：`scripts/zone-redis-sync.py`（systemd `zone-redis-sync.service`）

## 二、实现要点
1. **事件驱动双向同步**：订阅双侧 `__keyevent@0__:*`，写事件触发增量同步。
2. **原子复制**：`DUMP` + `RESTORE REPLACE`（保留数据类型与 TTL，覆盖 string/hash/list/set/zset）。
3. **冲突解决 LWW**：每 key 影子时间戳 `__sync:ts:<key>`（7 天过期）；事件驱动以"源侧当前时间"参与比较；周期对账时按存量时间戳、双 0 以 zone-a 优先（确定性 tie-break）。
4. **防回环**：同步前后值相等即跳过（RESTORE 触发的对侧事件因值一致被忽略）。
5. **周期对账兜底**：默认 30s 全量 SCAN 比差，覆盖事件丢失窗口（当前 key 总量级 ~1.8k，成本可忽略）。

## 三、实测结果
| 场景 | 结果 |
|---|---|
| zone-a 写 → zone-b 读（string） | ✅ `from-zone-a` |
| zone-b 写 → zone-a 读（string） | ✅ `from-zone-b`（首轮暴露 LWW 初始态 bug，已修复） |
| Hash 类型同步 | ✅ `hgetall` 全字段一致 |
| **并发冲突**（双侧同时写同 key） | ✅ 双侧收敛到后写者 `value-B-newer`（LWW 生效） |
| **zone-b 主库故障**（docker stop 6381） | ✅ zone-a 继续读写（outage 期间写入 `demo:sync:partition`） |
| **恢复对账追平** | ✅ 6381 重启后 ≤35s 自动追平 outage 期间数据 |
| 数据一致性 | 双侧 dbsize 一致（1871 = 1871） |

## 四、边界（诚实声明）
- 单机仿真：两侧主库同机，无双机房延迟/网络分区；同步为**异步**（RPO>0，窗口内可能丢最新写）；
- LWW 用"处理时刻"近似写入时刻（事件驱动），极端乱序下收敛但不保证严格"写入顺序"语义；
- Worker 单实例无高可用；未做 key 级流控/大 key 保护；生产级需 Redis Enterprise Active-Active/CRDT。
- 与 D3-7 的关系：D3-7（Client）负责"读本地、写主库"路由；D3-8（Server）让 zone-b 主库**可写**并与其他 zone 双向同步——两者组合才构成完整"双活"。

## 五、交付物
- `scripts/zone-redis-sync.py`（同步 Worker）+ `/etc/systemd/system/zone-redis-sync.service`
- `my-xhs-redis-zone-b` 容器（端口 6381，AOF 持久化，keyspace 通知开启）

## 六、Review 轮发现并修复（2026-09-18 同日）
1. **对账 tie-break 误删数据（严重）**：双时间戳为 0 时原逻辑"zone-a 优先"，会把仅存在于 zone-b 的 key 在 zone-a 侧视为"删除"并写入，导致数据丢失。修复为**存在优先**（仅单侧存在时以存在侧为准；双侧都存在才 zone-a 优先）。
   - 复测：worker 停止期间仅在 zone-b 写入无时间戳 key → 重启对账后双侧均为该值（未被删）✅
2. **事件通道无重连**：Redis 重启导致 pubsub 断开后监听线程静默死亡（仅剩 30s 对账兜底）。修复为**断线自动重连（3s 重试）**。
   - 复测：重启 zone-b Redis 后写入立即可同步至 zone-a ✅
3. 复核结果：common 单测全绿；content/cart/product 基线 zone 参数=0；双侧 dbsize 一致（1873=1873）、影子时间戳一致（945=945）。

## 七、遗留边界（复核确认）
- 同步覆盖全部业务 key（含 `myxhs:lock:*` 锁键）——仿真可接受，生产需按前缀白名单过滤（锁/幂等键不应跨 zone 复制）；
- LWW 用"事件处理时刻"近似写入时刻，极端乱序下保证收敛但不保证严格写入顺序语义；
- Worker 单实例（无 HA），断线期间依赖 30s 对账补偿（RPO>0）。
