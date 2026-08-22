package com.myxhs.ai.app.service.trace;

public final class TraceServiceLayerMapper {

    public static String layerOf(String service) {
        if (service == null) {
            return "";
        }
        if (service.contains("gateway") || service.contains("home")) {
            return "入口/聚合层";
        }
        if (service.contains("order") || service.contains("payment") || service.contains("inventory") || service.contains("coupon")) {
            return "交易链路";
        }
        if (service.contains("content") || service.contains("search") || service.contains("product") || service.contains("user") || service.contains("cart")) {
            return "业务服务层";
        }
        return "";
    }

    private TraceServiceLayerMapper() {
    }
}
