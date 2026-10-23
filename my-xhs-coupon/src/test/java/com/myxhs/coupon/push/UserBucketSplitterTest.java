package com.myxhs.coupon.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UserBucketSplitterTest {

    @Test
    @DisplayName("按条数切批：5 条 / 每批 2 → 2,2,1")
    void splitBySize() {
        List<Integer> items = IntStream.rangeClosed(1, 5).boxed().toList();
        List<List<Integer>> batches = UserBucketSplitter.split(items, 2, 800 * 1024, String::valueOf);
        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(2);
        assertThat(batches.get(2)).hasSize(1);
    }

    @Test
    @DisplayName("按字节切批：单条 10B、上限 15B → 每批 1 条")
    void splitByBytes() {
        List<Integer> items = IntStream.rangeClosed(1, 3).boxed().toList();
        List<List<Integer>> batches = UserBucketSplitter.split(
                items, 100, 15, i -> "0123456789");
        assertThat(batches).hasSize(3);
    }

    @Test
    @DisplayName("空列表 → 空批次")
    void empty() {
        assertThat(UserBucketSplitter.split(List.of(), 10, 100, String::valueOf)).isEmpty();
    }
}
