package com.myxhs.common.zone.admin;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.common.zone.ZoneConstants;
import com.myxhs.common.zone.ZoneContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zone 管理端点（D3-10 动态热切演示）。
 * <p>
 * 运行时切换当前 Zone（不重启、不改配置），触发 ZoneContext 属性变更事件：
 * 动态数据源热切换、LB zone 优先路由立即生效。
 * </p>
 * <p>默认关闭：{@code myxhs.availability.zone.admin.enabled=true} 开启；内部令牌保护。</p>
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/internal/zone")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.admin", name = "enabled", havingValue = "true")
public class ZoneAdminController {

    private final AccessTokenGuard accessTokenGuard;

    public ZoneAdminController(AccessTokenGuard accessTokenGuard) {
        this.accessTokenGuard = accessTokenGuard;
    }

    @GetMapping
    public R<Map<String, Object>> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        ZoneContext zoneContext = ZoneContext.get();
        result.put("zone", zoneContext.getZone());
        result.put("enabled", zoneContext.isEnabled());
        result.put("preferenceEnabled", zoneContext.isPreferenceEnabled());
        result.put("sameZoneMinAvailable", zoneContext.getPreferenceUpstreamSameZoneMinAvailable());
        return R.ok(result);
    }

    @PostMapping("/switch")
    public R<Map<String, Object>> switchZone(@RequestParam("zone") String zone,
                                             @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(401, "内部调用令牌无效");
        }
        if (zone == null || zone.isBlank()) {
            return R.fail(40002, "zone 不能为空");
        }
        ZoneContext zoneContext = ZoneContext.get();
        String from = zoneContext.getZone();
        zoneContext.setZone(zone.trim());
        System.setProperty(ZoneConstants.CURRENT_ZONE_PROPERTY_NAME, zone.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", zoneContext.getZone());
        return R.ok("zone switched", result);
    }
}
