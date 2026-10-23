package com.myxhs.coupon.sharding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 优惠券分片路由规格测试（C-017）
 * <p>
 * 与 sharding-config.yaml 的 INLINE 表达式保持一致：
 * db    = user_id % 2
 * table = (user_id DIV 2) % 16
 * 纯算法断言，不依赖数据库/ShardingSphere 运行时。
 * </p>
 */
class CouponShardingRouteTest {

    private static int db(long userId) {
        return (int) (userId % 2);
    }

    private static int table(long userId) {
        return (int) ((userId / 2) % 16);
    }

    @Test
    @DisplayName("路由规格：前 32 个 user_id 均匀落到 2 库 × 16 表")
    void routeFirst32() {
        assertThat(db(0)).isZero();
        assertThat(table(0)).isZero();
        assertThat(db(1)).isEqualTo(1);
        assertThat(table(1)).isZero();
        assertThat(db(2)).isZero();
        assertThat(table(2)).isEqualTo(1);
        assertThat(db(31)).isEqualTo(1);
        assertThat(table(31)).isEqualTo(15);
        assertThat(db(32)).isZero();
        assertThat(table(32)).isZero();
    }

    @Test
    @DisplayName("路由规格：大 user_id 边界（取模稳定、无负数）")
    void routeLargeUserId() {
        long userId = 9_876_543_210L;
        assertThat(db(userId)).isBetween(0, 1);
        assertThat(table(userId)).isBetween(0, 15);
        assertThat(db(userId)).isEqualTo((int) (userId % 2));
        assertThat(table(userId)).isEqualTo((int) ((userId / 2) % 16));
    }
}
