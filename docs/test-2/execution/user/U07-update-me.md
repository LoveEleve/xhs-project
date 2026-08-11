# U07-update-me — PUT /api/user/me
> 2026-08-10 | 链1 user | 需JWT+HMAC

## L1 正常: 更新nickname/avatar → 200, 返回新nickname=chaintest_u1_更新

## L2 数据: MySQL t_user.nickname 已更新 ✅

## 结果: ✅ 通过
