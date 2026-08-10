# my-xhs-inventory 已知故障与陷阱

## 一、TCC Fence 悬挂 (xid冲突)

- **现象**: Try先执行→库存冻结→确认失败→订单回滚→但Cancel后到→Fence表xid+branch_id已存在status=1→Cancel无法插入status=3
- **根因**: Try事务提交慢，Cancel先到Fence
- **应对**: TccFenceService.tryFence()双重判断——status=3判定SUSPENDED跳过

## 二、TCC 空回滚 (Cancel先于Try)

- **现象**: Cancel执行→Try后执行→冻结了不存在的库存
- **根因**: 分布式事务超时rollback发出Cancel→但Try消息延迟到达
- **应对**: TccFenceService.cancelFence()先INSERT status=3成功(SKIP)→Try到达时检查status=3→SUSPENDED

## 三、Outbox MQ超时回滚但Job重发

- **现象**: syncSend超时→cancelOutboxEvent→但Job扫描到同一outbox(3s后)重新投递→MySQL扣了Redis没扣
- **根因**: Outbox行已写入但event未消费成功→cancel改变了Redis但outbox行保留
- **应对**: InventoryOutboxSenderJob只重发status=0且<3s外的outbox(cancel时update status=1)

## 四、桶分片热点

- **现象**: 高并发时单一桶竞争→Redis Lua返回-1(桶库存不足)
- **根因**: 桶分片不够，热点SKU集中在少数桶
- **应对**: 初始化时桶数根据库存量动态计算(>10000 → 10桶, >1000 → 5桶)

## 五、预扣超时未释放

- **现象**: order创建→预扣成功→支付超时关单→预扣未释放→库存永久减少
- **根因**: 关单时releaseStock调用了MQ→MQ丢失→库存泄露
- **应对**: PreDeductTimeoutJob @Scheduled扫描30min未确认预扣→自动释放

## 六、连不上Redis

- **现象**: inventory I06直接500
- **根因**: Redis桶分片无法连接，total读不到
- **验证**: `redis-cli -p 26379 SENTINEL MASTER mymaster`
- **恢复**: 重启redis-sentinel → 主从自动切换

## 七、INTERNAL_TOKEN空值

- **现象**: order→Feign inventory I02返回403
- **根因**: `X-Internal-Call` header与`${myxhs.internal.token}`不匹配
- **修复**: application.yml `myxhs.internal.token=${INTERNAL_TOKEN:}`默认空→order调用方需显式传token
