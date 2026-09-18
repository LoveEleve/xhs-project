package com.myxhs.common.zone.redis.config;

import com.myxhs.common.zone.ZoneConstants;
import io.lettuce.core.ReadFrom;

/**
 * Zone 感知 Redis ReadFrom 解析器。
 * <p>
 * 当前 Zone 等于 slave-zone 时使用 {@link ReadFrom#REPLICA_PREFERRED}（读走本 Zone 副本、写走主库，
 * 副本不可用自动回主库）；其余情况使用 {@link ReadFrom#MASTER}（读写主库）。
 * </p>
 *
 * @since 1.0.0
 */
public final class ZoneRedisReadFromResolver {

    private ZoneRedisReadFromResolver() {
    }

    public static ReadFrom resolve(boolean enabled, String zone, String slaveZone) {
        if (!enabled || zone == null || zone.isBlank() || ZoneConstants.DEFAULT_ZONE.equals(zone)) {
            return ReadFrom.MASTER;
        }
        if (slaveZone != null && !slaveZone.isBlank() && zone.equals(slaveZone)) {
            return ReadFrom.REPLICA_PREFERRED;
        }
        return ReadFrom.MASTER;
    }
}
