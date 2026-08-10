# my-xhs-im 已知故障与陷阱

## 一、配置陷阱

### JWT secret 256位要求
- **现象**: 启动报错 "JWT secret key length must be at least 256 bits"
- **根因**: `jwt.secret` 或 `IM_JWT_SECRET` 短于32字节
- **修复**: 配置32字节+的密钥: `openssl rand -base64 32`

### IM表独立数据库
- **现象**: `SELECT * FROM t_chat_message` 报 "Table doesn't exist"
- **根因**: IM表在 `my_xhs_im` 库，非 `my_xhs_user`
- **验证**: `mysql -e "SHOW TABLES FROM my_xhs_im"`

## 二、WebSocket陷阱

### WS ticket 5分钟过期
- **现象**: WebSocket连接失败 "Invalid ticket"
- **根因**: JWT ticket 5分钟过期，获取后太久未用
- **应对**: 立即建立WebSocket连接

### WS重连时序
- **现象**: 断网后重建连接，消息丢失
- **根因**: WebSocket断开期间的消息已写MySQL，但客户端未拉取
- **应对**: 重连后自动调用 GET /messages/{peerId} 拉取历史

## 三、并发陷阱

### 未读数精确性
- **现象**: unread_count 偶尔不准
- **根因**: 发送时+1、对方标记已读时归零 → 并发UPDATE竞态
- **应对**: MySQL UPDATE WHERE unread_count > 0 防止负数

## 代码级缺陷（1 P0 + 7 P1，链7深审 2026-08-09）

### P0-C IM WebSocket Gateway白名单拦截（待验证）
- **位置**: `gateway/application.yml:269-298` 白名单缺`/api/im/ws`
- **修复**: 白名单加`/api/im/ws`

### M1 jwt.secret两处默认值不一致
- **位置**: `ImController.java:46`(空) vs `ImHandshakeInterceptor.java:28`(36字节)
- **修复**: 统一为`MyXhs@2026#JwtSecretKey!ForTokenSign`

### M2 路由竞态afterConnectionClosed误删新连接
- **位置**: `ImWebSocketHandler.java:59-65,106-114`
- **修复**: 改条件`if(sessions.get(userId)==session) unregisterRoute`

### M3 seqNo Redis INCR无异常处理
- **位置**: `ChatService.java:117`
- **修复**: 捕获返回NACK(客户端重发)

### M4 upsertConversation读改写非原子
- **位置**: `MessagePersistService.java:70-104`
- **修复**: SQL原子`UPDATE unread_count=unread_count+1`

### M5 IM未读双源(Redis+DB)无对账
### M6 handleRead清零与handleChat递增竞态
### M7 实例重启窗口跨实例消息静默丢失
