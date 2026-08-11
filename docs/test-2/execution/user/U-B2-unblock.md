# U-B2-unblock — DELETE /api/user/block/{targetUserId}
> 2026-08-10 | 链1 user | 需JWT+HMAC

## L1 正常: unblock → 200 操作成功

## L2 数据: Redis block set 移除 u2 ✅

## 结果: ✅ 通过
