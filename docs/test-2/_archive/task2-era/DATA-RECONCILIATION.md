# 数据完整性对账 — 交接文档 vs 实际环境 vs 既有产物

> 2026-08-10 | 目的: 在执行 Task 2 测试前，找出会直接导致翻车的文档矛盾/漂移，确定续跑还是重跑。

---

## 结论速览

| # | 问题 | 严重度 | 影响 |
|:--:|------|:--:|------|
| 1 | 验证码获取方式矛盾（日志法 vs Redis法） | 🔴 必翻车 | 照抄 curl 会 code 为空，注册/登录失败 |
| 2 | 用户名漂移（chaintest_c1 vs chaintest_u1） | 🔴 必翻车 | 31个文件用错误用户名，登录40108 |
| 3 | 端点ID三套命名 | 🟡 混乱 | 无法按 test-plan 引用既有产物 |
| 4 | 执行进度三态不清 | 🟡 起点不明 | 续跑/重跑无法判定 |
| 5 | 4模块 test-plan 无 execution-path | 🟡 脱节 | product/coupon/order/content-social 的 test-plan 与产物不对应 |

---

## 矛盾1: 验证码获取方式（🔴 执行必失败）

- **交接文档 §5.2 / pitfalls#7**（正确）: 验证码**不写日志**，从 Redis 读 `myxhs:user:captcha:{key}`（CaptchaService 已 P0 修复为纯文本存储）。
- **但 8 个文件仍用日志法** `grep "$KEY" /tmp/r_user.log`:
  - `business-docs/user/U01-captcha.md`
  - `business-docs/user/U03-login.md`
  - `business-docs/user/U14-register.md`
  - `business-docs/user/test-plan.md`
  - `business-docs/user/architecture.md`
  - `execution/user/U01-register.md`
  - `execution/user/U02-captcha.md`
  - `execution/user/U03-login.md`

**处置**: 统一改为 Redis 法（交接文档 §1.4 就是正确写法）:
```bash
KEY=$(curl -s http://localhost:19000/api/user/auth/captcha | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
CODE=$(python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print(r.get('myxhs:user:captcha:$KEY').decode())")
```

---

## 矛盾2: 用户名漂移（🔴 执行必失败）

- **MySQL 实测存在**: `chaintest_u1`(id=2086729019870457858) + `chaintest_u2` ✅（交接文档正确）
- **但 31 个文件用 `chaintest_c1`**（错误/过时）:
  - `business-docs/` + `execution/` 下的 user/product/cart/order 等
  - 0 个文件同时含两个名字 → 说明整体过期，非混用

**处置**: 31 个文件的 curl body 将 `chaintest_c1` → `chaintest_u1`（密码 `Test@123456`）。否则登录返回 40108 密码错误。

---

## 矛盾3: 端点ID三套命名（🟡 混乱）

同一端点在三处名字不同:

| 端点 | HANDOFF文档 | test-plan | execution实际文件 |
|------|:--:|:--:|:--:|
| 登录 | U03-login | U03-login | U03-login ✅ |
| 当前用户 | U06-me | U06-me | U11-me ❌ |
| 屏蔽 | U-B1-block | U-B1-block | U14-block ❌ |
| 购物车列表 | C04 | C04 | B02-cart-list ❌ |
| 通知列表 | N03-list | N03-list | T03-list ❌ |

**处置**: 以 **test-plan 的 execution-path 列为基准**（该列与 HANDOFF 一致），对已有产物做别名映射，勿直接按文件名匹配。

---

## 矛盾4: 执行进度三态不清（🟡 起点不明）

execution/ 已有**真实测试记录**（8-08 生成，50-60行/文件，非空壳）:

| 模块 | test-plan端点 | 已有产物 | 缺口 |
|------|:--:|:--:|:--:|
| user | 16 | 15 | 1 |
| product | 14 | 7 | 7 |
| cart | 10 | 7 | 3 |
| coupon | 7 | 3 | 4 |
| order | 23 | 2 | 21 |
| content-social | 38 | 1 | 37 |
| notification | 9 | 6 | 3 |
| im | 7 | 5 | 2 |
| counter | 3 | 0 | 3 |
| home | 7 | 0 | 7 |
| search | 18 | 0 | 18 |
| inventory | 10 | 0 | 10 |

另有 `execution/_archive/`（8-08 14:30 历史产物）与当前 execution 并列。

**处置**: 既有产物不能作废（是真实 L1-L4 记录），建议**续跑**：先复用/校验已有，只补缺口模块（counter/home/search/inventory + 各模块缺失端点）。

---

## 矛盾5: 4模块 test-plan 无 execution-path（🟡 脱节）

`product / coupon / order / content-social` 的 test-plan 中 grep 不到 `execution/...` 引用，但其 execution 目录却存在产物（P01*/N01*等）。这些模块的 test-plan 未与产物对接，执行顺序需从各自 README/architecture 重建。

---

## 处理决定（2026-08-10 已执行）

| # | 问题 | 决定 | 状态 |
|:--:|------|------|:--:|
| 1 | 验证码取法矛盾 | 日志法→Redis法（6文件）+ 2处prose | ✅ 已修 |
| 2 | 用户名漂移 | chaintest_c1→chaintest_u1（31文件） | ✅ 已修 |
| 3 | 端点ID三套命名 | (b) 建别名映射表，不改文件 → `ALIAS-MAPPING.md` | ✅ 已建 |
| 4 | 执行进度不清 | **全量重跑**（废弃 legacy，按 test-plan 基准ID重生成） | 📋 执行时落实 |
| 5 | 4模块test-plan无引用 | 补齐 product/coupon/order/content-social 的 execution-path 列 | ✅ 已补 |

> 现已全部12模块 test-plan 均含 `execution/` 产出引用。
> 重跑时统一按 test-plan 基准端点ID命名输出文件，旧 legacy 文件不再沿用。
