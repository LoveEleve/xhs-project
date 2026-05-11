# 收货地址管理

> 所属服务：my-xhs-user (9001) | 开发阶段：Phase-1 | 状态：⏳ 待开发

## 功能概要

- 收货地址 CRUD
- 默认地址设置
- 地址上限控制（最多20个）

## 涉及数据库表

- `t_user_address` — 收货地址表（5000万级）

## 关键技术点

- Cache Aside + 延迟双删保证缓存一致性
- Redis 缓存默认地址 `user:address:default:{userId}`

## 面试高频问题

- 缓存和数据库一致性怎么保证？
- 为什么用延迟双删而不是先删缓存再更新DB？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
