package com.myxhs.home.dubbo;

import com.myxhs.analytics.dubbo.AnalyticsDubboService;
import com.myxhs.cart.dubbo.CartDubboService;
import com.myxhs.content.api.dubbo.ContentDubboService;
import com.myxhs.coupon.dubbo.CouponDubboService;
import com.myxhs.counter.dubbo.CounterDubboService;
import com.myxhs.notification.dubbo.NotificationDubboService;
import com.myxhs.order.api.dubbo.OrderDubboService;
import com.myxhs.product.api.dubbo.ProductDubboService;
import com.myxhs.user.api.dubbo.UserDubboService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Dubbo Consumer 聚合门面
 * <p>
 * 统一管理所有 Dubbo 服务引用（Triple 协议），逐步替换 FeignClient 调用。
 * 提供与 FeignClient 相同的方法签名，确保 Service 层改动最小化。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DubboConsumerFacade {

    // ==================== 内容服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private ContentDubboService contentDubboService;

    // ==================== 商品服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private ProductDubboService productDubboService;

    // ==================== 订单服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private OrderDubboService orderDubboService;

    // ==================== 用户服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private UserDubboService userDubboService;

    // ==================== 计数服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private CounterDubboService counterDubboService;

    // ==================== 购物车服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private CartDubboService cartDubboService;

    // ==================== 优惠券服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private CouponDubboService couponDubboService;

    // ==================== 社交/分析服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private AnalyticsDubboService analyticsDubboService;

    // ==================== 通知服务 ====================
    @DubboReference(version = "1.0.0", timeout = 3000, retries = 0)
    private NotificationDubboService notificationDubboService;

    // ==================== 内容服务代理方法 ====================

    public Map<String, Object> getNoteDetail(Long noteId) {
        return contentDubboService.getNoteDetail(noteId);
    }

    public Map<String, Object> getCommentPage(Long noteId, int pageNum, int pageSize) {
        return contentDubboService.getCommentPage(noteId, pageNum, pageSize);
    }

    public Map<String, Object> getUserNotes(Long userId, int pageNum, int pageSize) {
        return contentDubboService.getUserNotes(userId, pageNum, pageSize);
    }

    // ==================== 商品服务代理方法 ====================

    public Map<String, Object> getSpuDetail(Long spuId) {
        return productDubboService.getSpuDetail(spuId);
    }

    public Map<String, Object> getSkuDetail(Long skuId) {
        return productDubboService.getSkuDetail(skuId);
    }

    // ==================== 订单服务代理方法 ====================

    public Map<String, Object> getOrderDetail(Long userId, Long orderId) {
        return orderDubboService.getOrderDetail(userId, orderId);
    }

    public List<Map<String, Object>> getUserOrders(Long userId, Integer status) {
        return orderDubboService.getUserOrders(userId, status);
    }

    // ==================== 用户服务代理方法 ====================

    public Map<String, Object> getUserPublicInfo(Long userId) {
        return userDubboService.getUserPublicInfo(userId);
    }

    // ==================== 计数服务代理方法 ====================

    public Map<String, Map<String, Long>> batchGetCounts(Map<String, Object> request) {
        return counterDubboService.batchGetCounts(request);
    }

    // ==================== 购物车服务代理方法 ====================

    public Map<String, Object> getCartList(Long userId) {
        return cartDubboService.getCartList(userId);
    }

    public Map<String, Integer> getCartCount(Long userId) {
        return cartDubboService.getCartCount(userId);
    }

    // ==================== 优惠券服务代理方法 ====================

    public List<Map<String, Object>> getAvailableCoupons(Long userId) {
        return couponDubboService.getAvailableCoupons(userId);
    }

    // ==================== 社交/分析服务代理方法 ====================

    public Map<String, Object> getFollowingList(Long userId, int page, int size) {
        return analyticsDubboService.getFollowingList(userId, page, size);
    }

    public Map<String, Object> getFollowerList(Long userId, int page, int size) {
        return analyticsDubboService.getFollowerList(userId, page, size);
    }

    public Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, String bizIds) {
        return analyticsDubboService.batchCheckLikeStatus(userId, bizType, bizIds);
    }

    public Map<String, Boolean> checkRelation(Long userId, Long targetUserId) {
        return analyticsDubboService.checkRelation(userId, targetUserId);
    }

    public Boolean checkFavoriteStatus(Long userId, Long noteId) {
        return analyticsDubboService.checkFavoriteStatus(userId, noteId);
    }

    // ==================== 通知服务代理方法 ====================

    public Map<String, Object> getUnreadCount(Long userId) {
        return notificationDubboService.getUnreadCount(userId);
    }
}
