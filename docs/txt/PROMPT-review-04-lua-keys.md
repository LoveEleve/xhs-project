验证 my-xhs 项目中所有 Lua 脚本的 KEYS 参数与 Java 调用方传入的 KEYS 数量是否一致。
逐个检查以下脚本：

1. prededuct.lua — 需求 KEYS=2+N（total + prededuct + N个bucket），检查 InventoryService.preDeduct() 传入的 KEYS 列表长度
2. release.lua — 需求 KEYS=3，检查 InventoryService.releaseStock()、rollbackPreDeduct()、PreDeductTimeoutJob.releasePreDeduct() 三个调用方传入的 KEYS 长度
3. follow_self.lua / follow_target.lua — 需求 KEYS=2+2，检查 FollowService.follow() 两步调用
4. unfollow_self.lua / unfollow_target.lua — 需求 KEYS=2+2，检查 FollowService.unfollow() 两步调用
5. favorite_atomic.lua — 需求 KEYS=1，检查 FavoriteService.favorite()
6. claim_coupon.lua / return_coupon.lua — 需求 KEYS=2，检查 CouponService 调用方
7. cart_add.lua / cart_remove.lua / cart_check_all.lua — 需求 KEYS=3/3/2，检查 CartService 调用方

输出不一致清单（如果有），没有不一致则输出"全部一致"
