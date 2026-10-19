package com.myxhs.im.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话关系 Mapper SQL 注解内容断言（C-013）——防关键语义回归（D1 软删清零 / 定向更新原子自增）。
 */
class ChatUserRelationMapperTest {

    private static String sql(String method, Class<?>... params) throws Exception {
        Method m = ChatUserRelationMapper.class.getMethod(method, params);
        return String.join(" ", m.getAnnotation(Update.class).value());
    }

    @Test
    @DisplayName("C-013 D1：软删 SQL 必须同置 unread_count=0（僵尸未读根治——防回归）")
    void softDeleteZeroesUnread() throws Exception {
        String sql = sql("softDeleteConversation", Long.class, Long.class);
        assertThat(sql).contains("is_deleted = 1").contains("unread_count = 0");
    }

    @Test
    @DisplayName("新消息定向更新：unread_count SQL 原子自增（读改写丢更新防回归）")
    void updateOnNewMessageAtomicIncrement() throws Exception {
        String sql = sql("updateOnNewMessage", Long.class, Long.class, long.class, String.class,
                int.class, int.class, java.time.LocalDateTime.class);
        assertThat(sql).contains("unread_count = unread_count +").contains("is_deleted = 0");
    }

    @Test
    @DisplayName("已读清零：定向 + 条件（不整行覆盖）")
    void resetUnreadDirected() throws Exception {
        String sql = sql("resetUnread", Long.class, Long.class);
        assertThat(sql).contains("unread_count = 0").contains("unread_count > 0");
    }
}
