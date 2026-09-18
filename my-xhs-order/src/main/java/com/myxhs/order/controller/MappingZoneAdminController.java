package com.myxhs.order.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.order.config.ZoneAwareMappingDataSourceConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 映射表动态数据源 · 内部管理端点（演示/验证用）
 * <ul>
 *   <li>GET  /api/order/internal/mapping-zone          查看当前路由与生效 server_id（1=master / 2=slave）</li>
 *   <li>POST /api/order/internal/mapping-zone/switch?target=master|slave   运行时切换</li>
 * </ul>
 * 仅在 order.mapping.zone-routing.enabled=true 时可启用；内部令牌保护。
 * 与 ShardingSphere 并存：分片表走主 DataSource，映射表走本动态数据源。
 */
@RestController
@RequestMapping("/api/order/internal/mapping-zone")
@ConditionalOnProperty(name = "order.mapping.zone-routing.enabled", havingValue = "true")
public class MappingZoneAdminController {

    private final JdbcTemplate mappingJdbcTemplate;
    private final AccessTokenGuard accessTokenGuard;

    public MappingZoneAdminController(@Qualifier("mappingJdbcTemplate") JdbcTemplate mappingJdbcTemplate,
                                      AccessTokenGuard accessTokenGuard) {
        this.mappingJdbcTemplate = mappingJdbcTemplate;
        this.accessTokenGuard = accessTokenGuard;
    }

    @GetMapping
    public R<Map<String, Object>> status(@RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅允许内部服务调用");
        }
        return R.ok(snapshot());
    }

    @PostMapping("/switch")
    public R<Map<String, Object>> switchTarget(@RequestParam("target") String target,
                                               @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅允许内部服务调用");
        }
        ZoneAwareMappingDataSourceConfig.switchTarget(target);
        return R.ok(snapshot());
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", ZoneAwareMappingDataSourceConfig.currentTarget());
        result.put("serverId", mappingJdbcTemplate.queryForObject("SELECT @@server_id", Integer.class));
        result.put("mappingRows", mappingJdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_order_no_mapping", Long.class));
        return result;
    }
}
