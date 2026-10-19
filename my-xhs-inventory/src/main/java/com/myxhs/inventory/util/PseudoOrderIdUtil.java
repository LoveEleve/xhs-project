package com.myxhs.inventory.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 伪订单ID派生工具（04B 提取自 OrderTransactionConsumer）
 * <p>
 * orderNo（字符串）→ SHA-256 前 8 字节（Big-endian signed long），作为库存域
 * "预扣/释放/确认"的幂等键（C-008 单键：inventory:{skuId}:prededuct:{pseudoOrderId}）。
 * </p>
 * <p>
 * ⚠️ 必须与 OrderService.derivePseudoOrderId（订单域，Feign 补偿路径使用）保持**算法完全一致**——
 * 历史事故 T-067：fold-hash(*31+char) 与 SHA-256 不一致 → 预扣 key 与释放 key 对不上 →
 * 库存永远无法释放（泄漏）。修改本方法前先核对订单域实现。
 * </p>
 */
public final class PseudoOrderIdUtil {

    private PseudoOrderIdUtil() {
    }

    /**
     * 派生伪订单ID：SHA-256 前 8 字节（Big-endian signed long）。
     */
    public static long derive(String orderNo) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(orderNo.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (digest[i] & 0xFF);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
