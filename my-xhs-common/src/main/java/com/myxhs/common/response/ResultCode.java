package com.myxhs.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码枚举
 * <p>
 * 错误码规范：
 * - 200：成功
 * - 400xx：客户端错误（参数校验、权限等）
 * - 500xx：服务端错误（系统异常、第三方服务异常等）
 * - 模块化前缀：
 *   - 1xxxx：用户模块
 *   - 2xxxx：内容模块
 *   - 3xxxx：电商模块（商品/订单/库存/优惠券）
 *   - 41xxx~49xxx：社交模块（关注/点赞/评论）
 *   - 5xxxx：基础设施模块（网关/IM/搜索）
 * </p>
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    // ==================== 通用成功/失败 ====================
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不允许"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // ==================== 参数校验 40001~40099 ====================
    PARAM_MISSING(40001, "缺少必要参数"),
    PARAM_INVALID(40002, "参数格式不正确"),
    PARAM_TYPE_ERROR(40003, "参数类型错误"),

    // ==================== 认证鉴权 40100~40199 ====================
    TOKEN_EXPIRED(40101, "Token已过期"),
    TOKEN_INVALID(40102, "Token无效"),
    TOKEN_REVOKED(40103, "Token已注销"),
    CAPTCHA_ERROR(40104, "验证码错误或已过期"),
    CAPTCHA_EXPIRED(40105, "验证码已过期"),
    ACCOUNT_LOCKED(40106, "账号已锁定，请稍后重试"),
    ACCOUNT_DISABLED(40107, "账号已被禁用"),
    PASSWORD_ERROR(40108, "密码错误"),

    // ==================== 幂等/限流/锁 40200~40299 ====================
    IDEMPOTENT_REJECT(40201, "请勿重复操作"),
    RATE_LIMIT_REJECT(40202, "请求过于频繁，请稍后重试"),
    LOCK_ACQUIRE_FAIL(40203, "操作过于频繁，请稍后重试"),

    // ==================== 用户模块 10001~19999 ====================
    USER_NOT_FOUND(10001, "用户不存在"),
    BLOCKED(10010, "已被对方拉黑，无法关注"),
    USERNAME_EXISTS(10002, "用户名已存在"),
    PHONE_EXISTS(10003, "手机号已注册"),
    EMAIL_EXISTS(10004, "邮箱已注册"),
    USERNAME_OR_PHONE_EXISTS(10005, "用户名或手机号已存在"),
    ADDRESS_NOT_FOUND(10006, "收货地址不存在"),
    ADDRESS_LIMIT_EXCEEDED(10007, "收货地址数量已达上限"),

    // ==================== 内容模块 20001~29999 ====================
    NOTE_NOT_FOUND(20001, "笔记不存在"),
    NOTE_STATUS_ERROR(20002, "笔记状态不允许此操作"),
    NOTE_AUDIT_PENDING(20003, "笔记审核中，暂不可操作"),
    COMMENT_NOT_FOUND(20004, "评论不存在"),
    COMMENT_CONTENT_ILLEGAL(20005, "评论内容包含敏感词"),
    NOTE_CONTENT_ILLEGAL(20006, "笔记内容包含敏感词"),
    TOPIC_NOT_FOUND(20007, "话题不存在"),

    // ==================== 电商模块 30001~39999 ====================
    PRODUCT_NOT_FOUND(30001, "商品不存在"),
    SKU_NOT_FOUND(30002, "SKU不存在"),
    PRODUCT_OFF_SHELF(30003, "商品已下架"),
    STOCK_NOT_ENOUGH(30004, "库存不足"),
    STOCK_DEDUCT_FAIL(30005, "库存扣减失败"),
    CART_ITEM_NOT_FOUND(30006, "购物车商品不存在"),
    CART_LIMIT_EXCEEDED(30007, "购物车商品数量已达上限"),
    ORDER_NOT_FOUND(30008, "订单不存在"),
    ORDER_STATUS_ERROR(30009, "订单状态不允许此操作"),
    ORDER_ALREADY_PAID(30010, "订单已支付"),
    ORDER_EXPIRED(30011, "订单已超时关闭"),
    COUPON_NOT_FOUND(30012, "优惠券不存在"),
    COUPON_ALREADY_RECEIVED(30013, "优惠券已领取"),
    COUPON_SOLD_OUT(30014, "优惠券已领完"),
    COUPON_EXPIRED(30015, "优惠券已过期"),
    COUPON_NOT_AVAILABLE(30016, "优惠券不满足使用条件"),
    PAYMENT_FAIL(30017, "支付失败"),
    SKU_STOCK_NOT_INITIALIZED(30018, "SKU库存未初始化"),

    // ==================== 社交模块 41001~49999 ====================
    ALREADY_FOLLOWED(41001, "已关注该用户"),
    NOT_FOLLOWED(41002, "未关注该用户"),
    CANNOT_FOLLOW_SELF(41003, "不能关注自己"),
    ALREADY_LIKED(41004, "已点赞"),
    NOT_LIKED(41005, "未点赞"),
    ALREADY_FAVORITED(41006, "已收藏"),
    NOT_FAVORITED(41007, "未收藏"),

    // ==================== 基础设施 50001~59999 ====================
    GATEWAY_ERROR(50001, "网关异常"),
    SERVICE_CALL_FAIL(50002, "服务调用失败"),
    MQ_SEND_FAIL(50003, "消息发送失败"),
    ES_QUERY_FAIL(50004, "搜索服务异常"),
    FILE_UPLOAD_FAIL(50005, "文件上传失败"),
    ;

    /** 错误码 */
    private final int code;

    /** 错误消息 */
    private final String message;

    /**
     * 获取格式化后的消息
     * <p>
     * 消息模板中的占位符 {} 会被依次替换为 args 中的值。
     * 如果没有占位符或 args 为空，返回原始消息。
     * </p>
     * <p>使用示例：</p>
     * <pre>{@code
     * // 消息模板: "库存不足"
     * ResultCode.STOCK_NOT_ENOUGH.getMessage() → "库存不足"
     *
     * // 消息模板: "库存不足: skuId={}, requested={}, available={}"
     * ResultCode.STOCK_NOT_ENOUGH.getMessage(skuId, requestedQty, availableQty)
     *   → "库存不足: skuId=12345, requested=10, available=3"
     * }</pre>
     * <p>
     * 为什么不用 MessageFormat：
     * MessageFormat 内部使用 StringBuffer + 正则解析，高并发下有性能瓶颈。
     * 本方法使用简单的 String.replace() 逐个替换 {}，O(n) 复杂度，无正则开销。
     * </p>
     *
     * @param args 消息占位符参数
     * @return 格式化后的消息
     */
    public String getMessage(Object... args) {
        if (args == null || args.length == 0) {
            return this.message;
        }
        String result = this.message;
        for (Object arg : args) {
            // 用 quoteReplacement：参数含 $ 或 \ 时 replaceFirst 会抛 IllegalArgumentException（业务错误变 500）
            result = result.replaceFirst("\\{\\}",
                    java.util.regex.Matcher.quoteReplacement(arg != null ? arg.toString() : "null"));
        }
        return result;
    }
}
