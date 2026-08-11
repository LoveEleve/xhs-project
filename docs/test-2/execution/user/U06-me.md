# U06-me — GET /api/user/me
> 2026-08-10 | 链1 user | 需JWT

## L1 正常: 带token → 200 username=chaintest_u1
- 异常: 无token → 401

## L2 数据: 从 MySQL t_user 读取 ✅

## 发现的问题
- 曾返回500(ServiceLoader NoSuchElementException): 根因=mvn clean删了运行中jar致懒加载失败, 重启后恢复(见pitfalls#45)。非代码bug。

## 结果: ✅ 通过
