package com.myxhs.common.zone.locator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 组合 Zone 定位器：按顺序取第一个成功定位的结果。
 *
 * @since 1.0.0
 */
public class CompositeZoneLocator implements ZoneLocator {

    private final List<ZoneLocator> locators;

    public CompositeZoneLocator(List<ZoneLocator> locators) {
        List<ZoneLocator> sorted = new ArrayList<>(locators);
        sorted.sort(Comparator.comparingInt(ZoneLocator::getOrder));
        this.locators = sorted;
    }

    @Override
    public String locate() {
        for (ZoneLocator locator : locators) {
            String zone = locator.locate();
            if (zone != null && !zone.isBlank()) {
                return zone.trim();
            }
        }
        return null;
    }
}
