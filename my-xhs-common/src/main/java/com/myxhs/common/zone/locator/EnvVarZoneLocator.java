package com.myxhs.common.zone.locator;

/**
 * 环境变量 Zone 定位器（MYXHS_ZONE）。
 *
 * @since 1.0.0
 */
public class EnvVarZoneLocator implements ZoneLocator {

    public static final String ENV_NAME = "MYXHS_ZONE";

    @Override
    public String locate() {
        return System.getenv(ENV_NAME);
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
