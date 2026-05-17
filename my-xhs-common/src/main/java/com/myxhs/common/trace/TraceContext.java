package com.myxhs.common.trace;

import lombok.Data;

/**
 * 全链路流量染色上下文
 * <p>
 * 统一管理 6 个染色标记，通过 ThreadLocal 在当前线程中传递。
 * 生命周期：请求进入时设置 → 业务处理中读取 → 请求结束后清理。
 * </p>
 * <p>
 * 染色标记来源：
 * - Gateway 入口注入（TrafficColoringFilter）
 * - 下游服务从 HTTP Header 恢复（TraceIdConfig 拦截器）
 * - MQ 消费者从 Message Header 恢复
 * - 异步线程从父线程继承（TaskDecorator）
 * </p>
 */
@Data
public class TraceContext {

    /** 全链路追踪 ID（串联所有服务的日志） */
    private String traceId;

    /** 用户 ID（下游服务获取当前用户） */
    private String userId;

    /** 灰度标记：beta / stable（灰度发布路由） */
    private String grayTag;

    /** API 版本号：v1 / v2（多版本 API 路由） */
    private String apiVersion;

    /** AB 测试分组：A / B / C（推荐策略/UI 实验） */
    private String abGroup;

    /** 压测标记：true / false（压测流量隔离写影子表） */
    private String pressureTest;
}
