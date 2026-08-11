# U14-register — POST /api/user/auth/register
> 2026-08-10 | 链1 user | 认证端点

## L1 正常路径
- 注册 chaintest_u3 → code=200 操作成功
- 异常: 重复注册 chaintest_u1 → code=10002 "用户名已存在"

## L2 数据
- MySQL t_user 新增 chaintest_u3 ✅

## 结果: ✅ 通过
