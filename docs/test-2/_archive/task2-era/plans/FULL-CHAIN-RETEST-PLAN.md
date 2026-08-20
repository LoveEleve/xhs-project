# my-xhs 全链路重测执行文档

> 2026-08-09 | 分层执行指南
> 方法论: [../methodology/TEST-METHODOLOGY.md](../methodology/TEST-METHODOLOGY.md)

---

## 一、七条业务链

```
链1 用户/认证 (12端点)      → user/
链2 产品 (10+5=15端点)      → product/ + search/
链3 购物车 (7端点)          → cart/
链4 券 (5端点)              → coupon/
链5 订单全生命周期 (12端点)  → order/
链6 内容/社交 (15端点)      → content-social/
链7 通知/IM (10端点)        → notification/ + im/
```

## 二、执行顺序与依赖

```
链1 用户(先做) → 链2 产品 → 链3 购物车 → 链4 券 → 链5 订单 → 链6 内容 → 链7 通知/IM
                       ↑             ↑                              ↑
                  链5下单需要     链3需要skuId                  链6需要用户
                  skuId/库存      链4需要券模板                  链7需要token
```

## 三、前置条件

| 条件 | 验证 |
|------|------|
| 16服务Nacos注册 | `for s in gateway user ...; do curl Nacos ...; done` |
| Token可用 | `cat /tmp/test_token.txt` + `curl /api/user/me` 返回200 |
| MySQL主从正常 | `mysql -P 3307 -e "SHOW SLAVE STATUS"` → IO+SQL双Yes |
| Redis Sentinel | `redis-cli -p 26379 SENTINEL MASTER mymaster` → ip=21.130.247.89 |
| Canal运行 | `curl http://127.0.0.1:11111` |
| XXL-Job 1min cron | `curl XXL-Job` → 全部 `0 * * * * ?` |

## 四、每端点执行规范

1. 读 `business-docs/{module}/{端点ID}.md` — 含源码分析+ASCII+业务逻辑+L2+L3
2. 按文件中的curl命令执行
3. 结果写入 `execution/{module}/{端点ID}.md` — 含HTTP响应+Redis/MySQL/MQ/SkyWalking验证
4. 禁止批量curl
5. 每链完成做：Prometheus快照 + MQ积压检查

## 五、文档索引

| 文档 | 路径 |
|------|------|
| 服务端点详细分析 | `business-docs/{module}/README.md` |
| 工程文档-分布式事务 | `engineering-docs/distributed-transactions.md` |
| 工程文档-监控管道 | `engineering-docs/monitoring-pipeline.md` |
| 工程文档-部署指南 | `engineering-docs/deployment-guide.md` |
| 测试执行产出 | `execution/{module}/{端点ID}.md` |
| 已知踩坑 | `execution/pitfalls.md` |

## 六、执行协议

```
环境:
  GATEWAY=http://localhost:19000
  REDIS_HOST=21.130.247.89:6379
  MYSQL_HOST=21.130.247.89:3306
  TOKEN=$(cat /tmp/test_token.txt)

特殊JVM参数:
  analytics:  -Dmanagement.admin-token=my-xhs-admin-token-2026
  notification: --spring.profiles.active=dev
  im:         -Djwt.secret=xhs-test-secret-key-2026-my-xhs-project-imag-service
```
