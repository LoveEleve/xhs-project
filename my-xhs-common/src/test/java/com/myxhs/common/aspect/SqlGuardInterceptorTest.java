package com.myxhs.common.aspect;

import com.myxhs.common.exception.SqlGuardBlockedException;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * SqlGuardInterceptor 单元测试（v2：判定 + 安全阻断）
 * <p>
 * 覆盖：默认不阻断 / 白名单阻断 / 豁免 / 冷却恢复 / 总开关
 * </p>
 */
class SqlGuardInterceptorTest {

    private static final String SQL = "SELECT * FROM t_order WHERE id = ?";

    private SqlGuardInterceptor newInterceptor(long slowMs, int threshold, long cooldownMs,
                                               boolean blockOnOpen, String blockPatterns, String exemptPatterns) {
        SqlGuardInterceptor interceptor = new SqlGuardInterceptor(null);
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        ReflectionTestUtils.setField(interceptor, "slowMs", slowMs);
        ReflectionTestUtils.setField(interceptor, "breakerThreshold", threshold);
        ReflectionTestUtils.setField(interceptor, "cooldownMs", cooldownMs);
        ReflectionTestUtils.setField(interceptor, "blockOnOpen", blockOnOpen);
        ReflectionTestUtils.setField(interceptor, "blockPatterns", blockPatterns);
        ReflectionTestUtils.setField(interceptor, "exemptPatterns", exemptPatterns);
        return interceptor;
    }

    private Invocation invocationReturning(String result) throws Throwable {
        StatementHandler handler = mock(StatementHandler.class);
        BoundSql boundSql = mock(BoundSql.class);
        when(boundSql.getSql()).thenReturn(SQL);
        when(handler.getBoundSql()).thenReturn(boundSql);
        Invocation invocation = mock(Invocation.class);
        when(invocation.getTarget()).thenReturn(handler);
        when(invocation.proceed()).thenReturn(result);
        return invocation;
    }

    @Test
    @DisplayName("默认 block-on-open=false：熔断开启也只观测不阻断")
    void shouldNotBlockWhenBlockOnOpenDisabled() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 60_000, false, "select", "");

        Invocation first = invocationReturning("ok");
        assertThat(interceptor.intercept(first)).isEqualTo("ok");

        Invocation second = invocationReturning("ok");
        assertThat(interceptor.intercept(second)).isEqualTo("ok");
    }

    @Test
    @DisplayName("block-on-open=true + 命中白名单：冷却期内阻断并抛异常")
    void shouldBlockWhenWhitelisted() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 60_000, true, "select", "");

        Invocation first = invocationReturning("ok");
        assertThat(interceptor.intercept(first)).isEqualTo("ok");

        Invocation second = invocationReturning("ok");
        assertThatThrownBy(() -> interceptor.intercept(second))
                .isInstanceOf(SqlGuardBlockedException.class)
                .hasMessageContaining("SQL 连续慢查询已触发保护");
    }

    @Test
    @DisplayName("block-on-open=true 但未命中白名单：不阻断")
    void shouldNotBlockWhenNotWhitelisted() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 60_000, true, "insert into t_x", "");

        assertThat(interceptor.intercept(invocationReturning("ok"))).isEqualTo("ok");
        assertThat(interceptor.intercept(invocationReturning("ok"))).isEqualTo("ok");
    }

    @Test
    @DisplayName("豁免名单命中：不计数、不阻断")
    void shouldNeverBlockExemptSql() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 60_000, true, "select", "t_order");

        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.intercept(invocationReturning("ok"))).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("冷却到期后自动恢复，不再阻断")
    void shouldRecoverAfterCooldown() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 20, true, "select", "");

        interceptor.intercept(invocationReturning("ok"));
        assertThatThrownBy(() -> interceptor.intercept(invocationReturning("ok")))
                .isInstanceOf(SqlGuardBlockedException.class);

        Thread.sleep(40);
        assertThat(interceptor.intercept(invocationReturning("ok"))).isEqualTo("ok");
    }

    @Test
    @DisplayName("总开关关闭：完全不介入")
    void shouldSkipWhenDisabled() throws Throwable {
        SqlGuardInterceptor interceptor = newInterceptor(-1, 1, 60_000, true, "select", "");
        ReflectionTestUtils.setField(interceptor, "enabled", false);

        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.intercept(invocationReturning("ok"))).isEqualTo("ok");
        }
    }
}
