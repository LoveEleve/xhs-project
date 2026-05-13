package com.myxhs.common.constants;

/**
 * Redis Key 常量
 * <p>
 * 统一管理所有 Redis Key 的前缀和模式，避免硬编码。
 * 命名规范：{服务}:{模块}:{业务}:{标识}
 * </p>
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
        // 常量类禁止实例化
    }

    // ==================== 用户服务 ====================

    /** 用户信息缓存 user:info:{userId} */
    public static final String USER_INFO = "user:info:";

    /** Access Token user:token:access:{userId} */
    public static final String USER_TOKEN_ACCESS = "user:token:access:";

    /** Refresh Token user:token:refresh:{userId} */
    public static final String USER_TOKEN_REFRESH = "user:token:refresh:";

    /** Token 黑名单 user:token:blacklist:{jti} */
    public static final String USER_TOKEN_BLACKLIST = "user:token:blacklist:";

    /** 图形验证码 user:captcha:{key} */
    public static final String USER_CAPTCHA = "user:captcha:";

    /** 邮箱验证码 user:email:code:{email} */
    public static final String USER_EMAIL_CODE = "user:email:code:";

    /** 注册分布式锁 user:register:lock:{phone} */
    public static final String USER_REGISTER_LOCK = "user:register:lock:";

    /** 登录失败计数 user:login:fail:{username} */
    public static final String USER_LOGIN_FAIL = "user:login:fail:";

    /** 账号锁定标记 user:login:lock:{username} */
    public static final String USER_LOGIN_LOCK = "user:login:lock:";

    // ==================== 内容服务 ====================

    /** 笔记详情缓存 note:detail:{noteId} */
    public static final String NOTE_DETAIL = "note:detail:";

    /** 笔记计数缓存 note:count:{noteId} */
    public static final String NOTE_COUNT = "note:count:";

    // ==================== 社交服务 ====================

    /** 关注列表 follow:list:{userId} */
    public static final String FOLLOW_LIST = "follow:list:";

    /** 粉丝列表 follow:fans:{userId} */
    public static final String FOLLOW_FANS = "follow:fans:";

    /** 点赞集合 like:{bizType}:{bizId} */
    public static final String LIKE_SET = "like:";

    /** 收藏集合 favorite:{userId} */
    public static final String FAVORITE_SET = "favorite:";

    // ==================== 计数服务 ====================

    /** 计数缓存 counter:{targetType}:{targetId} */
    public static final String COUNTER = "counter:";

    /** 计数 Buffer counter:buffer */
    public static final String COUNTER_BUFFER = "counter:buffer";

    // ==================== 电商服务 ====================

    /** 商品缓存 product:spu:{spuId} */
    public static final String PRODUCT_SPU = "product:spu:";

    /** SKU 缓存 product:sku:{skuId} */
    public static final String PRODUCT_SKU = "product:sku:";

    /** 购物车 cart:{userId} */
    public static final String CART = "cart:";

    /** 库存预扣减 inventory:pre:{skuId} */
    public static final String INVENTORY_PRE = "inventory:pre:";

    /** 库存缓存 inventory:stock:{skuId} */
    public static final String INVENTORY_STOCK = "inventory:stock:";

    /** 优惠券库存 coupon:stock:{couponId} */
    public static final String COUPON_STOCK = "coupon:stock:";

    /** 用户领券记录 coupon:user:{userId}:{couponId} */
    public static final String COUPON_USER = "coupon:user:";

    // ==================== 搜索服务 ====================

    /** 热搜榜 search:hot:ranking */
    public static final String SEARCH_HOT_RANKING = "search:hot:ranking";

    /** 搜索建议 search:suggest:{prefix} */
    public static final String SEARCH_SUGGEST = "search:suggest:";

    // ==================== Feed 流 ====================

    /** 用户收件箱（推模式） feed:inbox:{userId} */
    public static final String FEED_INBOX = "feed:inbox:";

    /** 用户发件箱（拉模式） feed:outbox:{userId} */
    public static final String FEED_OUTBOX = "feed:outbox:";

    // ==================== ID 生成 ====================

    /** 流水号自增 id:serial:{prefix}:{date} */
    public static final String ID_SERIAL = "id:serial:";

    /** 雪花 ID WorkerId id:worker:{serviceName} */
    public static final String ID_WORKER = "id:worker:";
}
