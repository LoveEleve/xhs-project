# cart Lua 与外部证据补充

## 1. Lua 逐脚本对账

### `cart_add.lua`

- 读取/写入 `items`、`checked`、`sort` 三个 key：`src/main/resources/lua/cart_add.lua:1-66`
- 已有 SKU：数量累加并限制最大数量，返回实际数量：`cart_add.lua:23-35`
- 新 SKU：检查 HLEN 上限，写 Hash、Set、ZSet，返回 `10000 + quantity`：`cart_add.lua:37-62`
- 满容量返回 `-1`：`cart_add.lua:41-43`

### `cart_merge_item.lua`

- 已有 SKU：使用 max(quantity)，不改变 checked，返回 `10000 + quantity`：`cart_merge_item.lua:19-26`
- 新 SKU：检查品种上限，写三结构，返回 quantity：`cart_merge_item.lua:28-37`
- 满容量实际返回 `0`：`cart_merge_item.lua:29-30`
- 文件注释同时写 `-1`，但代码没有 `-1` 分支：`cart_merge_item.lua:14-18`

### `cart_remove.lua`

- 删除 Hash、checked Set、sort ZSet：`cart_remove.lua:10-29`
- 删除成功返回 `1`，商品不存在返回 `0`：`cart_remove.lua:18-29`

### `cart_check_item.lua`

- 先检查 SKU 是否在 items Hash
- checked=true 时 SADD，false 时 SREM
- 成功返回 `1`，不存在返回 `0`：`cart_check_item.lua:10-17`

### `cart_check_all.lua`

- 取消全选直接 DEL checked Set，返回 `0`：`cart_check_all.lua:22-25`
- 全选读取 HKEYS 后重建 checked Set，返回 SKU 数量：`cart_check_all.lua:27-40`
- 注释中的 key 写成 `myxhs:myxhs:cart` 是错误，Java 实际传入 `myxhs:cart`：`cart_check_all.lua:9-10`、`CartService.java:90-97`

### `cart_update_quantity.lua`

- 先 HEXISTS，商品不存在返回 `0`
- 存在时 HSET 新数量并返回 `1`：`cart_update_quantity.lua:8-11`

## 2. Product fallback 精确语义

Product Feign fallback 返回失败响应时，`skuExists` 会返回 false；只有 Feign 调用直接抛异常进入 catch 时才返回 true：`ProductFeignFallbackFactory.java:27-36`、`CartService.java:625-636`。

因此问题不是“所有 Product 不可用都会放行”，而是“异常路径 fail-open、失败响应路径 fail-closed”，两条路径不一致。列表同时校验 SKU 和 SPU 状态：`CartService.java:447-452`；加购校验目前只检查 SKU status，未检查 `spuStatus`，可能出现加购成功后列表标记失效。

## 3. 外部 schema 证据

这些文件不属于 cart 34 个模块候选文件，但用于验证外部数据契约：

- `sql/init-all.sql:404-415`：`t_cart_item` 与 `uk_user_sku`
- `sql/migration/content/V1__init_content.sql:154-165`：模块 migration 中的购物车表定义
- `docs/test-3/review/observability-events-ddl.sql:37-51`：`t_cart_event` 历史观测 DDL
- `deploy/docker/my-xhs-deploy-zip/sql/init-all.sql:412-423`：部署包购物车表定义

## 4. 当前测试证据边界

本轮从仓库根目录成功执行：

```text
mvn -pl my-xhs-cart -am test
```

结果：
- `CartServiceTest`：13 个，0 failures/errors
- `CartSyncConsumerTest`：2 个，0 failures/errors
- cart 合计：15 个通过
- common 合计：53 个通过

这些结果证明当前 Mockito 单元路径通过，不代表真实 Redis Lua、MySQL、RocketMQ、Feign、锁和对账并发链路通过。
