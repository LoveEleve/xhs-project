# my-xhs-im 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| I-L1-01 | 签发 WS ticket | POST /api/im/ws/ticket | JWT(ws_ticket type, 5min) | ✅ |
| I-L1-02 | 会话列表 | GET /api/im/conversations | 返回会话+未读 | ✅ |
| I-L1-03 | 历史消息 | GET /api/im/messages/{peerId} | 分页历史 | ✅ |
| I-L1-04 | 标记已读 | POST /read/10002 | ✅ 200 幂等 |
| I-L1-05 | 未读计数 | GET /api/im/unread-count | 各会话未读 | ✅ |
| I-L1-06 | 在线人数 | GET /api/im/online-count | 在线连接数 | ✅ |

## L2 数据与消息

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| I-L2-01 | ticket JWT type=ws_ticket | ticket 解析 | ✅ |
| I-L2-02 | WS 握手鉴权边界 | 无/非法 ticket | ✅ 拒绝 |
| I-L2-03 | 合法 ticket 握手 | 带 ticket 升级 | ✅ 101 |
| I-L2-04 | 消息持久化 | 双端 WS 收发 → t_chat_message | ✅ 10001→10002落库 |
| I-L2-05 | 离线消息 | u2离线时u1发→u2上线收OFFLINE | ✅ count=1补发 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| I-L3-01 | 消息幂等 | 服务端生成msgId | ✅ 唯一不回执重复 |
| I-L3-02 | 已读/未读一致 | read 后未读清零 | ✅ REST层 |
| I-L3-03 | 双实例同会话经Redis pub/sub到达 | ✅ testuser→im1/testuser2→im2 跨实例送达 |
| I-L3-04 | 一致性哈希 | 同会话固定实例 | ✅ TreeMap 150虚拟节点 |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| I-L4-01 | WS 连接数指标 | ✅ Prometheus端点暴露 |
| I-L4-02 | ✅ IM路由订阅日志 |
| I-L4-03 | TraceId跨WS | ❌ 未实现(ImMessage/RouteMessage/Handler均无traceId字段或MDC传播, 消息可追溯性依赖msgId) |

## 已实测
- I-L1-01/02/03/05/06、I-L2-01/02/03 ✅（REST 全通 + 握手鉴权 fail-closed）
- 已实测CHAT/ACK/持久化/历史; 离线补发/跨实例/typing 待专项
