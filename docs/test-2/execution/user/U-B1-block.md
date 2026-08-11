# U-B1-block — POST /api/user/block/{targetUserId}
> 2026-08-10 | 链1 user | 需JWT+HMAC

## L1 正常: block chaintest_u2 → 200 操作成功
- 曾403 "HMAC密钥已过期": 改密码后需重登(会话密钥轮换), 重登后通过

## L2 数据: Redis block set 含 u2 ✅

## 结果: ✅ 通过
