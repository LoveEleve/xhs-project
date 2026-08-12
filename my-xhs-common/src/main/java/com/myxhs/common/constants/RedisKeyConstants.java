package com.myxhs.common.constants;

/**
 * Redis Key 常量
 * <p>
 * 统一管理所有 Redis Key 的前缀和模式，避免硬编码。
 * 命名规范：myxhs:{服务}:{模块}:{业务}:{标识}
 * 所有 Key 统一以 myxhs: 开头，防止多项目共享 Redis 时 Key 冲突。
 * </p>
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
        // 常量类禁止实例化
    }

    // ==================== 项目前缀 ====================

    /** 项目级前缀，所有 Key 必须以此开头 */
    public static final String PROJECT_PREFIX = "myxhs:";

    // ==================== 用户服务 ====================

    /** 用户信息缓存 myxhs:user:info:{userId} */
    public static final String USER_INFO = PROJECT_PREFIX + "user:info:";

    /** Access Token myxhs:user:token:access:{userId} */
    public static final String USER_TOKEN_ACCESS = PROJECT_PREFIX + "user:token:access:";

    /** Refresh Token myxhs:user:token:refresh:{userId} */
    public static final String USER_TOKEN_REFRESH = PROJECT_PREFIX + "user:token:refresh:";

    /** Token 黑名单 myxhs:user:token:blacklist:{jti} */
    public static final String USER_TOKEN_BLACKLIST = PROJECT_PREFIX + "user:token:blacklist:";

    /** HMAC 签名密钥（per-session）myxhs:user:hmac:secret:{userId} */
    public static final String USER_HMAC_SECRET = PROJECT_PREFIX + "user:hmac:secret:";

    /** 图形验证码 myxhs:user:captcha:{key} */
    public static final String USER_CAPTCHA = PROJECT_PREFIX + "user:captcha:";

    /** 邮箱验证码 myxhs:user:email:code:{email} */
    public static final String USER_EMAIL_CODE = PROJECT_PREFIX + "user:email:code:";

    /** 注册分布式锁 myxhs:user:register:lock:{phone} */
    public static final String USER_REGISTER_LOCK = PROJECT_PREFIX + "user:register:lock:";

    /** 默认收货地址ID缓存 myxhs:user:address:default:{userId} */
    public static final String USER_ADDRESS_DEFAULT = PROJECT_PREFIX + "user:address:default:";

    /** 登录失败计数 myxhs:user:login:fail:{username} */
    public static final String USER_LOGIN_FAIL = PROJECT_PREFIX + "user:login:fail:";

    /** 账号锁定标记 myxhs:user:login:lock:{username} */
    public static final String USER_LOGIN_LOCK = PROJECT_PREFIX + "user:login:lock:";

    /** IP 登录失败计数 myxhs:user:login:fail:ip:{ip}（P2-9：单源 DoS 拦截） */
    public static final String USER_LOGIN_FAIL_IP = PROJECT_PREFIX + "user:login:fail:ip:";

    /** IP 锁定标记 myxhs:user:login:lock:ip:{ip}（P2-9） */
    public static final String USER_LOGIN_LOCK_IP = PROJECT_PREFIX + "user:login:lock:ip:";

    /** 账号失败来源 IP 集合 myxhs:user:login:fail:ips:{username}（P2-9：仅多 IP 才锁账号） */
    public static final String USER_LOGIN_FAIL_IPS = PROJECT_PREFIX + "user:login:fail:ips:";

    /** 用户屏蔽列表 myxhs:user:block:{userId} */
    public static final String USER_BLOCK_LIST = PROJECT_PREFIX + "user:block:";

    /** 收货地址操作锁 myxhs:user:address:lock:{userId} */
    public static final String USER_ADDRESS_LOCK = PROJECT_PREFIX + "user:address:lock:";

    /** Token 刷新并发锁 myxhs:token:refresh:lock:{jti} */
    public static final String TOKEN_REFRESH_LOCK = PROJECT_PREFIX + "token:refresh:lock:";

    // ==================== 内容服务 ====================

    /** 笔记详情缓存 myxhs:note:detail:{noteId} */
    public static final String NOTE_DETAIL = PROJECT_PREFIX + "note:detail:";

    /** 用户笔记列表缓存 myxhs:note:list:user:{userId} */
    public static final String NOTE_LIST_USER = PROJECT_PREFIX + "note:list:user:";

    /** 笔记计数缓存 myxhs:note:count:{noteId} */
    public static final String NOTE_COUNT = PROJECT_PREFIX + "note:count:";

    /** 评论列表缓存 myxhs:comment:list:{noteId} */
    public static final String COMMENT_LIST = PROJECT_PREFIX + "comment:list:";

    /** 评论计数缓存 myxhs:comment:count:{noteId} */
    public static final String COMMENT_COUNT = PROJECT_PREFIX + "comment:count:";

    // ==================== 社交服务 ====================

    /** 关注列表 myxhs:follow:list:{userId} */
    public static final String FOLLOW_LIST = PROJECT_PREFIX + "follow:list:";

    /** 粉丝列表 myxhs:follow:fans:{userId} */
    public static final String FOLLOW_FANS = PROJECT_PREFIX + "follow:fans:";

    /** 点赞集合 myxhs:like:{bizType}:{bizId} */
    public static final String LIKE_SET = PROJECT_PREFIX + "like:";

    /** 收藏集合 myxhs:favorite:{userId} */
    public static final String FAVORITE_SET = PROJECT_PREFIX + "favorite:";

    // ==================== 计数服务 ====================

    /** 计数缓存 myxhs:counter:{targetType}:{targetId} */
    public static final String COUNTER = PROJECT_PREFIX + "counter:";

    /** 计数 Buffer myxhs:counter:buffer */
    public static final String COUNTER_BUFFER = PROJECT_PREFIX + "counter:buffer";

    /** MQ 去重 myxhs:counter:dedup:{msgId}（2小时TTL） */
    public static final String COUNTER_DEDUP = PROJECT_PREFIX + "counter:dedup:";

    // ==================== 电商服务 ====================

    /** 商品缓存 myxhs:product:spu:{spuId} */
    public static final String PRODUCT_SPU = PROJECT_PREFIX + "product:spu:";

    /** SKU 缓存 myxhs:product:sku:{skuId} */
    public static final String PRODUCT_SKU = PROJECT_PREFIX + "product:sku:";

    /** 购物车 myxhs:cart:{userId} */
    public static final String CART = PROJECT_PREFIX + "cart:";

    /** 库存预扣减 myxhs:inventory:pre:{skuId} */
    public static final String INVENTORY_PRE = PROJECT_PREFIX + "inventory:pre:";

    /** 库存缓存 myxhs:inventory:stock:{skuId} */
    public static final String INVENTORY_STOCK = PROJECT_PREFIX + "inventory:stock:";

    /** 优惠券库存（已废弃：CouponService 自行管理 Key，使用 myxhs:coupon:{%s}:stock 格式） */
    @Deprecated
    public static final String COUPON_STOCK = PROJECT_PREFIX + "coupon:stock:";

    /** 用户领券记录 myxhs:coupon:user:{userId}:{couponId} */
    public static final String COUPON_USER = PROJECT_PREFIX + "coupon:user:";

    // ==================== 搜索服务 ====================

    /** 热搜榜实时排行 myxhs:search:hot:realtime */
    public static final String SEARCH_HOT_REALTIME = PROJECT_PREFIX + "search:hot:realtime";

    /** 热搜滑动窗口分钟桶 myxhs:search:window:{yyyyMMddHHmm} */
    public static final String SEARCH_WINDOW = PROJECT_PREFIX + "search:window:";

    /** 热搜人工置顶 myxhs:search:hot:pinned */
    public static final String SEARCH_HOT_PINNED = PROJECT_PREFIX + "search:hot:pinned";

    /** 热搜人工屏蔽 myxhs:search:hot:blocked */
    public static final String SEARCH_HOT_BLOCKED = PROJECT_PREFIX + "search:hot:blocked";

    /** 热搜反作弊-用户限频 myxhs:search:antispam:user:{userId}:{keywordHash} */
    public static final String SEARCH_ANTISPAM_USER = PROJECT_PREFIX + "search:antispam:user:";

    /** 热搜反作弊-IP限频 myxhs:search:antispam:ip:{ip} */
    public static final String SEARCH_ANTISPAM_IP = PROJECT_PREFIX + "search:antispam:ip:";

    /** 搜索建议 myxhs:search:suggest:{prefix} */
    public static final String SEARCH_SUGGEST = PROJECT_PREFIX + "search:suggest:";

    // ==================== 推荐系统 ====================

    /** Item-CF 相似矩阵 myxhs:recommend:itemcf:{noteId} → ZSet(相似noteId, 相似度) */
    public static final String RECOMMEND_ITEMCF = PROJECT_PREFIX + "recommend:itemcf:";

    /** 用户兴趣标签 myxhs:recommend:user:tags:{userId} → Hash(tag, weight) */
    public static final String RECOMMEND_USER_TAGS = PROJECT_PREFIX + "recommend:user:tags:";

    /** 全局热门池 myxhs:recommend:hot:global → ZSet(noteId, hotScore) */
    public static final String RECOMMEND_HOT_GLOBAL = PROJECT_PREFIX + "recommend:hot:global";

    /** 关注用户最新内容 myxhs:recommend:following:latest:{userId} → ZSet(noteId, timestamp) */
    public static final String RECOMMEND_FOLLOWING_LATEST = PROJECT_PREFIX + "recommend:following:latest:";

    /** 用户已曝光内容 myxhs:recommend:seen:{userId} → HyperLogLog */
    public static final String RECOMMEND_SEEN = PROJECT_PREFIX + "recommend:seen:";

    // ==================== Feed 流 ====================

    /** 用户收件箱（推模式） myxhs:feed:inbox:{userId} */
    public static final String FEED_INBOX = PROJECT_PREFIX + "feed:inbox:";

    /** 用户发件箱（拉模式） myxhs:feed:outbox:{userId} */
    public static final String FEED_OUTBOX = PROJECT_PREFIX + "feed:outbox:";

    // ==================== ID 生成 ====================

    /** 流水号自增 myxhs:id:serial:{prefix}:{date} */
    public static final String ID_SERIAL = PROJECT_PREFIX + "id:serial:";

    /** 雪花 ID WorkerId myxhs:id:worker:{serviceName} */
    public static final String ID_WORKER = PROJECT_PREFIX + "id:worker:";
}
