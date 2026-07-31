# Canal 索引同步链路缺失 — 问题说明（给中间件团队）

> 验证日期：2026-07-31
> 状态：**已修复**（2026-07-31）

---

## 根因

Canal 1.1.7 使用 JDK 17 不兼容，binlog decoder 静默罢工（服务在跑但不下发事件）。
中间件团队已切换 Kona JDK 8（OpenJDK 1.8.0_502 Tencent Kona 8.0.27）并重启三个实例。

## 验证

```
1. UPDATE t_note SET title='Canal修复验证-xxx' → MySQL 成功
2. 10 秒内 ES note_index 同步 ✅
3. UPDATE t_note SET status=-1 → ES status=-1（软删）✅
```

## 中间件团队已确认

- note_instance → mysql-bin.000005:140448 ✅
- product_instance → 同上 ✅
- inventory_instance → MySQL 13309 当前位点 ✅

