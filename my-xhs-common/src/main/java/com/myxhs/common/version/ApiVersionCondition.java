package com.myxhs.common.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.condition.RequestCondition;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 版本匹配条件
 * 
 * Spring RequestMappingHandlerMapping 的 getMappingForMethod() 
 * 会遍历所有 RequestCondition 来判断哪个方法匹配当前请求。
 * 
 * 匹配规则：
 * 1. 请求 Header Accept-Version 存在 → 精确匹配
 * 2. 请求 Header Accept-Version 不存在 → 匹配最新版本（数值最大的）
 */
public class ApiVersionCondition implements RequestCondition<ApiVersionCondition> {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionCondition.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)$");
    private static final String HEADER_VERSION = "Accept-Version";

    private final String version;
    private final int major;
    private final int minor;

    public ApiVersionCondition(String version) {
        this.version = version;
        if (version == null || version.isEmpty()) {
            log.warn("ApiVersion value is null or empty, defaulting to 0.0");
            this.major = 0;
            this.minor = 0;
            return;
        }
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.matches()) {
            int parsedMajor = Integer.parseInt(matcher.group(1));
            int parsedMinor = Integer.parseInt(matcher.group(2));
            if (parsedMajor < 0) {
                log.warn("ApiVersion major version is negative [{}], defaulting to 0.0", version);
                this.major = 0;
                this.minor = 0;
            } else {
                this.major = parsedMajor;
                this.minor = parsedMinor;
            }
        } else {
            log.warn("ApiVersion [{}] does not match expected format 'major.minor', defaulting to 0.0", version);
            this.major = 0;
            this.minor = 0;
        }
    }

    @Override
    public ApiVersionCondition combine(ApiVersionCondition other) {
        // 方法级别的注解覆盖类级别
        return new ApiVersionCondition(other.version);
    }

    @Override
    public ApiVersionCondition getMatchingCondition(HttpServletRequest request) {
        String headerVersion = request.getHeader(HEADER_VERSION);
        if (headerVersion == null || headerVersion.isEmpty()) {
            // 未指定版本：返回 this（所有版本都匹配，由 compareTo 选最大）
            return this;
        }
        // 精确匹配
        if (this.version.equals(headerVersion)) {
            return this;
        }
        return null;
    }

    @Override
    public int compareTo(ApiVersionCondition other, HttpServletRequest request) {
        // 版本号大的优先（最新版本作为默认）
        int majorCompare = Integer.compare(this.major, other.major);
        if (majorCompare != 0) return -majorCompare; // 降序：大的在前
        return -Integer.compare(this.minor, other.minor);
    }
}
