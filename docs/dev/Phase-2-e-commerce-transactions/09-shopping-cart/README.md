# 购物车

> 所属服务：my-xhs-cart (9008) | 开发阶段：Phase-2 | 状态：⏳ 待开发

## 功能概要

- 加入购物车 / 修改数量 / 删除
- 购物车列表查询
- 匿名购物车 → 登录后合并
- 购物车商品数量上限（99件/品，总200品）

## 涉及数据库表

- 无（纯 Redis 存储）

## 关键技术点

- **Redis 三方案对比**：Hash vs Set vs ZSet
  - Hash：`cart:{userId}` → field=skuId, value=quantity（✅ 选定方案）
  - ZSet：score=加入时间，支持按时间排序
  - Set：仅判断是否在购物车
- 匿名购物车：`cart:anonymous:{deviceId}` → 登录后 HGETALL + HMSET 合并
- 购物车快照与下单解耦

## 面试高频问题

- 购物车为什么用 Redis 不用 MySQL？
- 匿名购物车怎么和登录用户合并？
- 购物车里的商品价格变了怎么处理？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
