# my-xhs-counter 已知故障与陷阱

## 一、SOCIAL_TOPIC双写(计数翻倍)
- **现象**: 点赞后like_count+2
- **根因**: CounterEventConsumer+FeedConsumer同时消费SOCIAL_TOPIC→双重UPDATE
- **修复**: 限定CounterEventConsumer独占计数UPDATE; FeedConsumer仅Feed递送

## 二、去重key冲突
- **现象**: 同msgId两次消息→第二次被Redis Set去重跳过→计数丢失
- **根因**: `myxhs:counter:dedup:{msgId}` 24h TTL→MQ重投超过24h→第二次计数加不上
- **修复**: 对账Job补偿; 重投次数<5，重投间隔<24h

## 三、Buffer攒批丢失
- **现象**: 内存攒批未满→服务重启→未刷MySQL的计数丢失
- **根因**: Buffer阈值100条，低流量时长时间不满
- **修复**: 定时flush(30s)+shutdown hook强制刷

## 四、Redis不可用降级
- **现象**: counter查询返回0
- **根因**: Redis挂了，CacheAside无兜底→降级到MySQL直接查
- **验证**: `redis-cli -p 26379 SENTINEL MASTER mymaster`

## 五、对账延迟
- **现象**: 点赞后立即查对账→值不一致
- **根因**: Buffer未刷→MySQL旧值; MQ延迟
- **应对**: 测试中对账前等5秒Buffer刷新
