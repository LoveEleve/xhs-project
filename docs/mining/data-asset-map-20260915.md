# 数据资产地图（2026-09-15，面试"数据模型"用）

## 1. MySQL 表（按库）
- **user**：t_user（uk_username/phone）、t_user_address、t_id_segment（uk_biz_tag）
- **analytics**：t_follow（uk_user_follow）、t_like（uk_user_biz）、t_favorite（uk_user_note）
- **notification**：t_notification（uk_aggregate 防重复聚合）、t_push_template（uk_type）、t_push_task、t_push_task_fail
- **im**：t_chat_message（idx_conversation_seq）、t_chat_user_relation（uk_user_peer）
- **content**：t_note、t_comment、t_topic（uk_name）、t_user_behavior（行为流水→搜索数据源）、t_hot_search_snapshot、**t_local_message**（Feed 本地消息：status 0/1/2/3 + push_cursor 断点续推）、t_note_event（事件流水）
- **counter**：t_counter（uk_target_count）
- **product**：t_category、t_spu、t_sku、t_product_behavior（append-only）
- **cart**：t_cart_item（uk_user_sku）、t_cart_event（msg_id 幂等；uk_msg_id 迁移仅在部署包）
- **coupon**：t_coupon_template、t_user_coupon（uk_claim_no=MQ msgId 幂等）、**t_coupon_outbox**（uk_claim_no）
- **order 公共库**：t_order_no_mapping（uk_order_no，非分片键反查）
- **payment**：t_payment（uk_payment_no）、t_refund（uk_refund_no）、t_payment_event
- **order_0..3 分片**：t_order/t_order_item/**t_local_message**/t_order_snapshot/**t_order_event**（uk_order_event_seq）各 _0..3（共 20 张/库）
- **inventory**：t_inventory（uk_sku_id，available/locked/freezing）、**t_tcc_fence**（PK xid+branch）、t_tcc_freeze_detail、**t_inventory_outbox**（uk_order_sku_action）、t_inventory_compensation、**t_inventory_prededuct_idem**（PK order+sku）
- **my_xhs_ai**：ai_session/message/approval/session_grant/audit/feedback（V1）+ ai_audit_chain（V2）+ ai_session_summary（V3）；⚠️ 旧 ai-app 在同库另有同名 `ai_message`（不同结构，已下线，注意不要混用）

## 2. Redis Key（前缀 myxhs:；行号≈RedisKeyConstants/各服务）
- 用户：user:info 30min、token:access 30min/refresh 7d、blacklist TTL=剩余有效期、hmac:secret、captcha 5min、login:fail 30min/lock 15min（账/IP 分离）
- 商品/内容：note:detail（双删）、comment:list、sensitive-word:list+reload 频道、product:spu 逻辑过期 30min/物理 2h、bloom:spu、category:tree 2h
- 购物车：`cart:{uid}:items` Hash + `:checked` Set + `:sort` ZSet + `:cleared`，TTL 30d（hash tag 同 slot）
- 库存（无 myxhs 前缀）：`inventory:{sku}:total|bucket:n|bucket:count`、**prededuct:{order} Hash**、**prededuct:index ZSet(过期索引)**、refund 24h、hot:window ZSet 10s
- 券/订单/支付/网关：coupon:{tpl}:stock/claimed、order:idempotent 24h、order:create:lock 10s、payment:*（paying 30min/refunding 7d/status 7d/30min）、gateway:nonce 5min
- Feed/推荐/搜索：feed:inbox ZSet(500/7d)、feed:outbox、push:progress 1h、user:bigv 10min、note:deleted 5min、search:hot:realtime 1h、search:window 分钟桶 2h、antispam:*、recommend:itemcf/hot/seen(HLL)
- IM/通知：im:route/online 90s(=心跳×3)、im:seq INCR、im:unread Hash、im:offline ZSet(1000/7d)、notification:unread String+Hash、agg:{uid}:... 当天剩余、sse 路由 30s、sse:ticket 30s
- 通用：msg:idempotent:{biz}、限流 ZSet 滑窗、counter:{t}:{id}:{c} 30d、dedup 2h、like:note/user Set、follow ZSet、lock:cache:* + 空值占位 2min

## 3. RocketMQ（19 topic + 24 消费组）
| Topic | 生产→消费 | 特性 |
|---|---|---|
| SOCIAL_TOPIC | analytics/content → counter+analytics+home+search 组 | 普通（Set 幂等抗乱序） |
| FEED_TOPIC | content → home-feed-push(3) | 写扩散 |
| NOTIFICATION_TOPIC | content/analytics → notification-event(3) | 普通 |
| INVENTORY_TOPIC | inventory(Outbox) → inventory-deduct(5) | 普通+Outbox |
| INVENTORY_CACHE_TOPIC | canal → inventory-cache-evict(5) | CDC 缓存失效 |
| ORDER_TRANSACTION_TOPIC | order → inventory-order-transaction(5) | **事务消息+回查** |
| ORDER_CLOSE_TOPIC | order → order-close(5) | **延迟消息 30min** |
| ORDER_COMPENSATION_TOPIC | order → order-compensation(3) | 补偿 |
| PAY_RESULT_TOPIC | payment → payment-pay-result(5)+order-pay-result(3) | 普通 |
| REFUND_RESULT_TOPIC | payment → payment-refund-result(5)+order-refund-result(3) | 普通 |
| CART_TOPIC | cart → cart-sync(3)+cart-event-sink(3) | 普通 |
| COUPON_CLAIM_TOPIC | coupon(Outbox) → coupon-claim(5) | 普通 |
| COUPON_RETURN_REDIS_REPAIR_TOPIC | coupon → repair(5) | 普通 |
| PRODUCT/NOTE_INDEX_TOPIC | canal → *-index-sync(3) | CDC→ES |
| RECOMMEND_BEHAVIOR_TOPIC | search → recommend-behavior(3) | 普通 |
| CACHE_EVICT_TOPIC | 各服务 → user-cache-evict(3) | 缓存失效广播 |
| DEFAULT_RETRY_TOPIC | order（本地消息兜底） | 重投 |
- 派生：`%RETRY%<group>`、`%DLQ%<group>`；无 ORDERLY 消费（顺序靠 uk_order_event_seq + ES ExternalGte）

## 4. Elasticsearch 索引
| 索引 | 要点 | 链路/保留 |
|---|---|---|
| note_index | 3 分片；title ik_max_word/content ik_smart；**ExternalGte + version=updated_at(ms)** | canal→NOTE_INDEX_TOPIC；无 ILM |
| product_index | 3 分片；price scaled_float×100；image keyword(不索引) | canal→PRODUCT_INDEX_TOPIC；无 ILM |
| suggest_index | completion(ik)+weight | 任务写入 |
| myxhs-logs-YYYY.MM.DD | 日志；level.keyword 等；AI 检索主力 | filebeat/logstash；**ILM 30 天删除、0 副本** |
| xhs_ai_knowledge | AI 知识卡（全量重建式） | KnowledgeIndexer |
