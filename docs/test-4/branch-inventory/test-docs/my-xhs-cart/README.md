# my-xhs-cart 测试重点

## 当前状态

- cart 非 `target` 文件基线：34 个
- 34 个候选文件已读取；核心 Service/Consumer/Job/Lua 已完成源码审查
- `CartServiceTest` 13 个、`CartSyncConsumerTest` 2 个，历史 Surefire 报告显示共 15 个通过；当前重新执行因 common 依赖解析失败，不能称为本轮测试通过
- AI 不属于本模块范围；鉴权只做基础 Header/内部调用检查

## L1 业务

- 加购、改数量、删除、单选、全选/取消全选
- 匿名/登录购物车合并
- 清空购物车与清空后重新加购
- 购物车列表的有效商品、失效商品、勾选和金额
- Product 不存在、下架、Feign 明确失败和依赖异常

## L2 数据

- Redis `items` Hash、`checked` Set、`sort` ZSet 三结构一致性
- 6 个 Lua 的返回值、上限、数量截断、并发和 key slot
- MySQL `t_cart_item` 的 INSERT/UPDATE/DELETE、唯一键、时间字段
- MQ `CART_TOPIC` action/tag、消费者状态、幂等和重试/DLQ
- Redis 丢失恢复、cleared marker、MySQL 对账修复

## L3 质量

- 清空与加购并发
- CHECK/CHECK_ALL/DELETE/ADD 乱序和同毫秒事件
- 对账与用户操作并发
- 定时全量对账与手动单用户对账并发
- Redis lock 过期、续租和安全释放
- Product fallback 明确失败与异常失败两条路径
- 合并 50 项的 Feign、Lua、MQ 放大
- Redis 故障、主从切换、MQ 失败和恢复

## L4 可观测性

- Cart Redis/MQ 失败、事件延迟、重试、DLQ、对账差异指标
- TraceId 跨 cart、Product Feign、RocketMQ Consumer
- 日志中 userId、SKU、事件 body 和凭据脱敏
- Actuator/Prometheus 实际依赖和访问保护

## 当前阻断

- 六个 Lua 尚未进行真实 Redis 集成验证
- 清空屏障、事件版本、对账锁和 Redis 恢复尚未进行并发运行验证
- MySQL 时间字段、唯一键和 MQ topic/tag 需要真实环境核对
- 业务测试等待核心模块分析完成后统一按 L1 -> L4 执行
