package com.myxhs.search.service;

import com.myxhs.common.constants.RedisKeyConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 热搜键族集群安全单测（C-011 补遗）
 * <p>
 * 背景：RECORD_SCRIPT 声明 4 个 KEYS——修复前全无 hash tag（CRC16 实算跨 4 slot），
 * 真 Redis Cluster 下 EVAL 被 CROSSSLOT 直接拒绝（catch 静默降级 → 热搜记录全挂）。
 * </p>
 * <p>
 * 本测试用 **Redis 同款 CRC16(CCITT) + hash tag 提取规则实算 slot**（非字符串断言），
 * 锁定：① RECORD_SCRIPT 四键同 slot；② 热搜族（pinned/blocked/window）同 slot；
 * ③ realtime 与 :tmp 同 slot（RENAME 集群合法）。
 * </p>
 */
class HotSearchKeySlotTest {

    private static final int SLOT_COUNT = 16384;

    /** Redis Cluster slot 计算：CRC16(key) % 16384；含 {...} 时只哈希 tag 内容（Redis hash tag 规则） */
    private static int slot(String key) {
        int s = key.indexOf('{');
        int e = key.indexOf('}');
        String hashed = (s >= 0 && e > s + 1) ? key.substring(s + 1, e) : key;
        return crc16(hashed) % SLOT_COUNT;
    }

    /** CRC-16/CCITT（poly 0x1021，init 0x0000——与 Redis crc16.c 一致） */
    private static int crc16(String s) {
        int crc = 0;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    @Test
    @DisplayName("RECORD_SCRIPT 四键同 slot：antispam(user/ip) + window + blocked（{hot} tag）")
    void recordScriptKeysSameSlot() {
        String userKey = RedisKeyConstants.SEARCH_ANTISPAM_USER + "123:手机";
        String ipKey = RedisKeyConstants.SEARCH_ANTISPAM_IP + "192.168.0.1";
        String bucketKey = RedisKeyConstants.SEARCH_WINDOW + "202610192000";
        String blockedKey = RedisKeyConstants.SEARCH_HOT_BLOCKED;

        int expected = slot(userKey);
        assertThat(slot(ipKey)).as("antispam:ip 与 antispam:user 同 slot").isEqualTo(expected);
        assertThat(slot(bucketKey)).as("window 与 antispam:user 同 slot").isEqualTo(expected);
        assertThat(slot(blockedKey)).as("blocked 与 antispam:user 同 slot").isEqualTo(expected);
    }

    @Test
    @DisplayName("热搜族同 slot：pinned/blocked/window（防后续脚本混用踩跨-slot 坑）")
    void hotFamilySameSlot() {
        int expected = slot(RedisKeyConstants.SEARCH_HOT_BLOCKED);
        assertThat(slot(RedisKeyConstants.SEARCH_HOT_PINNED)).isEqualTo(expected);
        assertThat(slot(RedisKeyConstants.SEARCH_WINDOW + "202610192000")).isEqualTo(expected);
    }

    @Test
    @DisplayName("realtime 与 :tmp 同 slot（RENAME 集群合法）")
    void realtimeRenamePairSameSlot() {
        String realtime = RedisKeyConstants.SEARCH_HOT_REALTIME;
        assertThat(slot(realtime + ":tmp")).as("tmp 与 realtime 同 slot").isEqualTo(slot(realtime));
    }
}
