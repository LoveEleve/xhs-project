# my-xhs-home Feed首页BFF
> 7端点 | HomeController+FeedTestController | 19015

## 端点
| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| H01 | GET | /api/home/feed | Feed流(大V拉取+小V推送) |
| H02 | GET | /api/home/note/{noteId} | 笔记聚合(5路Feign) |
| H03 | GET | /api/home/product/{spuId} | 商品聚合(3路Feign) |
| H04 | GET | /api/home/user/{targetUserId} | 用户主页(4路Feign) |
| H05 | GET | /api/home/cart | 购物车聚合(3路Feign) |
| H06 | POST | /api/home/test/push-inbox | 测试推收件箱(@Profile dev) |
| H07 | POST | /api/home/test/push-outbox | 测试推发件箱(@Profile dev) |

## BFF聚合层
每个端点调用2-5路Feign批量聚合数据返回给前端

## Redis Key
- `myxhs:feed:inbox:{uid}` - 收件箱(ZSet)
- `myxhs:feed:outbox:{uid}` - 发件箱(ZSet,大V模式)
- `myxhs:user:bigv:{uid}` - 大V标记
- `myxhs:follow:list:{uid}` - 关注列表
