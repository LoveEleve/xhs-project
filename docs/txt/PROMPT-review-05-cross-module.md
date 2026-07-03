对 my-xhs 项目进行跨模块数据流完整性验证，重点检查以下 4 条核心链路：

1. IM 消息流（M4改造后）：
   查 ChatService.handleChat() → Pub/Sub im:route:{serverId} → ImRouteSubscriber.onMessage() → pushToUser()
   检查：ChatService 是否已移除 RocketMQTemplate 依赖？ImRouteSubscriber 是否在推送失败时调用 storeOfflineMessage？

2. Feed 推送流（M2改造后）：
   查 NoteService.publishNote() → t_local_message 同事务写入 → asyncSend → onSuccess markSent → FeedMessageRetryJob 补偿
   检查：publishDraft 是否也写了 t_local_message？FeedMessageRetryJob 的 incrementRetry 是否有 off-by-one？

3. 库存流（M9改造后）：
   查 OrderTransactionConsumer → InventoryService.preDeduct() → 暂停标记检查 → 热点检测 → resizeBuckets
   检查：pauseKey 是否在 resizeBuckets finally 中删除？inventoryAsyncExecutor 线程池是否正确？

4. 会话保序流（M8改造后）：
   查 ChatService.handleChat() → seqNo INCR → saveMessageWithTransaction → push JSON 含 seqNo → RouteMessage 含 seqNo → ImRouteSubscriber 含 seqNo
   检查：pushOfflineMessages 是否按 seqNo 排序？getMessageHistory 是否按 seqNo 倒序？

输出每条链路的验证结果（通过/有问题）
