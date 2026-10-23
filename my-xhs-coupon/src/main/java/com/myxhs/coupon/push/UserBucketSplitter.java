package com.myxhs.coupon.push;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 用户批量分割（C-017 P3）
 * <p>
 * 同时受"条数上限"和"消息体字节上限"约束（RocketMQ 建议 ≤ 1MB，这里取 800KB 安全值）。
 * </p>
 */
public final class UserBucketSplitter {

    private UserBucketSplitter() {
    }

    public static <T> List<List<T>> split(List<T> items, int maxBatchSize, int maxBytes,
                                          Function<T, String> serializer) {
        List<List<T>> batches = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return batches;
        }
        List<T> current = new ArrayList<>();
        int currentBytes = 0;
        for (T item : items) {
            int bytes = serializer.apply(item).getBytes(StandardCharsets.UTF_8).length;
            if (!current.isEmpty()
                    && (current.size() >= maxBatchSize || currentBytes + bytes > maxBytes)) {
                batches.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(item);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }
}
