# my-xhs-counter 计数服务
> 3端点 | CounterController(/api/counter) | 19004

## 端点
| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| CT01 | GET | /api/counter/get | 查询(Redis→MySQL) |
| CT02 | POST | /api/counter/batch-get | 批量(Pipeline) |
| CT03 | POST | /api/counter/reconcile | 对账(X-Admin) |

## MQ写入
`CounterEventConsumer`: SOCIAL_TOPIC 10事件→Redis incr+Set去重→Buffer→batchUpdate t_counter

## Redis/MySQL
- `myxhs:counter:{type}:{id}:{countType}` + `dedup:{msgId}`
- my_xhs_counter.t_counter
