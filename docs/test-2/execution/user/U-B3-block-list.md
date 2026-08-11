# U-B3-block-list — GET /api/user/block/list
> 2026-08-10 | 链1 user | 需JWT+HMAC

## L1 正常: block u2 后 → ['2086729025960587265']; unblock后 → []

## L2 数据: Redis block set 一致 ✅

## 结果: ✅ 通过
