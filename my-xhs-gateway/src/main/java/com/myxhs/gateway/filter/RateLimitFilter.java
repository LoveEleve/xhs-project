package com.myxhs.gateway.filter;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sentinel 限流熔断过滤器
 * <p>
 * 职责：
 * 1. 基于 Sentinel 实现接口级 QPS 限流
 * 2. 限流时返回统一格式的 429 响应
 * <p>
 * 限流维度设计：
 * - 接口级限流：按 route ID 限流，保护单个服务不被打崩
 *   例：order-service 限流 500 QPS，防止订单接口被打爆
 * - 用户级限流：按 userId + route ID 限流，防止单用户恶意刷接口
 *   例：单用户下单限流 10 QPS，防刷单
 *   【当前状态】用户级限流依赖 Sentinel 的 GatewayParamFlowRule + 请求参数解析，
 *   需要 Gateway 感知 userId（AuthFilter 注入的 X-User-Id Header），
 *   后续实现：配置 paramItem 从 X-User-Id Header 提取用户标识
 * <p>
 * 限流算法选择：
 * - Sentinel 默认使用滑动窗口（LeapArray），精确控制窗口内请求数
 * - 对比固定窗口：避免窗口切换瞬间的 2 倍流量突刺
 * - 对比令牌桶：令牌桶允许突发（桶内积攒令牌后可瞬间消费），API 限流不需要突发能力
 * - 对比漏桶：漏桶匀速出水，适合流量整形，不适合 API 限流
 * <p>
 * 设计决策：
 * - 当前使用本地配置 + @PostConstruct 初始化限流规则
 * - 【重要】@PostConstruct 加载的规则存储在 JVM 内存中，重启后丢失。
 *   生产环境应使用 Nacos 数据源持久化规则，实现：
 *   1. 规则持久化到 Nacos，重启后自动加载
 *   2. 多网关实例共享规则，通过 Nacos 推送实现实时同步
 *   3. 运维人员可通过 Nacos Console 动态调整规则，无需重启
 * - 自定义 BlockRequestHandler 返回统一 JSON 格式，与 GatewayAuthFilter 的 401 格式保持一致
 * <p>
 * 分布式考虑：
 * - Sentinel 是单机限流，每台网关实例独立计算 QPS
 * - 如果需要集群限流，需要部署 Sentinel Token Server
 * - 当前阶段单机限流足够，后续如需集群限流可扩展
 */
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final GatewayProperties gatewayProperties;

    /** Sentinel Gateway 适配过滤器（委托执行） */
    private final SentinelGatewayFilter sentinelGatewayFilter;

    /** 无 metadata 时的默认限流 QPS */
    private static final int DEFAULT_QPS = 100;

    public RateLimitFilter(ObjectMapper objectMapper,
                           org.springframework.context.ApplicationContext applicationContext,
                           GatewayProperties gatewayProperties) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
        this.gatewayProperties = gatewayProperties;
        // SentinelGatewayFilter 无参构造即可，限流异常由自定义 BlockHandler 处理
        this.sentinelGatewayFilter = new SentinelGatewayFilter();
    }

    /**
     * 初始化自定义 BlockHandler（限流响应处理器）
     * <p>
     * 注意：仅注册 BlockHandler，不加载限流规则。
     * 限流规则的加载由 {@link #onApplicationReady()} 处理。
     */
    @PostConstruct
    public void init() {
        initBlockHandler();
    }

    /**
     * 应用启动完成后加载 Sentinel 限流规则
     * <p>
     * 使用 ApplicationReadyEvent 而非 @PostConstruct 的原因：
     * - @PostConstruct 执行时，Spring Cloud Alibaba Sentinel 的 Nacos 数据源尚未完成初始化
     * - 导致 GatewayRuleManager.getRules() 返回空，误判为"Nacos无规则"而加载本地兜底规则
     * - 本地兜底规则的 loadRules() 会覆盖后续 Nacos 推送的规则
     * - ApplicationReadyEvent 在所有 Bean 初始化完成后触发，此时 Nacos 数据源已注册监听器
     * <p>
     * 规则加载策略（关键设计）：
     * 1. 如果配置了 Nacos 数据源 → 不加载本地兜底规则（即使当前 GatewayRuleManager 为空）
     *    原因：Nacos Config Client 是异步拉取配置的，ApplicationReadyEvent 触发时规则可能尚未到达
     *    但监听器已注册，Nacos 推送后会自动加载规则到 GatewayRuleManager
     *    如果此时调用 loadRules 加载本地规则，会覆盖后续 Nacos 推送的规则
     * 2. 如果未配置 Nacos 数据源 → 加载本地兜底规则（开发/测试环境兜底）
     * 3. 运行时通过 Nacos Console 修改规则，Sentinel 会实时感知并更新
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        boolean nacosDatasourceConfigured = isNacosDatasourceConfigured();

        if (nacosDatasourceConfigured) {
            // Nacos 数据源已配置，等待异步推送规则
            log.info("[Gateway-Sentinel] Nacos数据源已配置，等待规则异步推送（当前规则数: {}）",
                    GatewayRuleManager.getRules().size());

            // 真空期兜底：30 秒后若 Nacos 规则仍未到达，加载本地兜底规则
            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
                if (GatewayRuleManager.getRules().isEmpty()) {
                    log.warn("[Gateway-Sentinel] Nacos规则30秒未到达，加载本地兜底规则");
                    initFlowRules();
                } else {
                    log.info("[Gateway-Sentinel] Nacos规则已到达(规则数: {})，跳过本地兜底",
                            GatewayRuleManager.getRules().size());
                }
            });
        } else {
            // 未配置 Nacos 数据源，使用本地兜底规则
            initFlowRules();
            log.info("[Gateway-Sentinel] 未配置Nacos数据源，使用本地兜底限流规则");
        }
    }

    /**
     * 检查是否配置了 Sentinel Nacos 数据源
     * <p>
     * 通过 Environment 判断 spring.cloud.sentinel.datasource.flow.nacos.server-addr 是否存在
     */
    private boolean isNacosDatasourceConfigured() {
        try {
            String serverAddr = applicationContext.getEnvironment()
                    .getProperty("spring.cloud.sentinel.datasource.flow.nacos.server-addr");
            return serverAddr != null && !serverAddr.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 委托给 Sentinel Gateway Filter 执行限流判断
        // Sentinel 内部会根据 route ID 或 API 分组匹配规则
        return sentinelGatewayFilter.filter(exchange, chain);
    }

    @Override
    public int getOrder() {
        // 在 HMAC 签名校验之后执行
        return Ordered.HIGHEST_PRECEDENCE + 2500;
    }

    /**
     * 从 yml 路由 metadata 加载限流规则（唯一数据源）
     * <p>
     * 规则来源：GatewayProperties 读取 application.yml 中定义的所有路由（不依赖 Nacos 异步发现）
     * - 逐一读取每个路由的 metadata.rate-limit-qps 值
     * - 未配置 rate-limit-qps 的路由使用默认值 {@link #DEFAULT_QPS}
     * - 排除 actuator 等非业务路由（id 含 "CompositeDiscoveryClient"）
     */
    private void initFlowRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        int loaded = 0;

        for (RouteDefinition rd : gatewayProperties.getRoutes()) {
            if (rd.getId() == null || rd.getId().contains("CompositeDiscoveryClient")) {
                continue;
            }
            int qps = readQpsFromMetadata(rd);
            rules.add(new GatewayFlowRule(rd.getId())
                    .setCount(qps)
                    .setIntervalSec(1));
            log.info("[Gateway-Sentinel] 加载限流规则: route={}, qps={}", rd.getId(), qps);
            loaded++;
        }

        if (loaded == 0) {
            log.warn("[Gateway-Sentinel] 未找到任何路由限流配置，规则集为空");
        }

        GatewayRuleManager.loadRules(rules);
        log.info("[Gateway-Sentinel] 限流规则加载完成，共 {} 条", loaded);
    }

    /**
     * 从路由定义中读取 rate-limit-qps 配置
     * <p>
     * metadata 格式示例（application.yml）：
     * <pre>
     *   metadata:
     *     rate-limit-qps: 50
     * </pre>
     */
    private int readQpsFromMetadata(RouteDefinition rd) {
        Map<String, Object> metadata = rd.getMetadata();
        if (metadata != null && metadata.containsKey("rate-limit-qps")) {
            Object val = metadata.get("rate-limit-qps");
            if (val instanceof Number) {
                return ((Number) val).intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(val));
            } catch (NumberFormatException ignored) {
                log.warn("[Gateway-Sentinel] route={} 的 rate-limit-qps 值非法: {}，使用默认值 {}",
                        rd.getId(), val, DEFAULT_QPS);
            }
        }
        return DEFAULT_QPS;
    }

    /**
     * 初始化自定义限流响应处理器
     * <p>
     * 被限流时返回统一格式的 429 响应，与鉴权失败的 401 响应格式保持一致：
     * {"code": 429, "message": "请求过于频繁，请稍后再试", "data": null}
     * <p>
     * 注意：必须同时注册 Gateway 适配器（SentinelGatewayFilter）与 WebFlux 适配器
     * （SentinelWebFluxFilter，spring-cloud-starter-alibaba-sentinel 自动装配）两个 BlockHandler。
     * T-106（2026-08-15 回归发现）：Sentinel 1.8.8 的 DefaultBlockRequestHandler 编译于 Spring 5.x
     * （调用 ServerResponse.status(HttpStatus)），WebFlux 6.1.6 已改为 HttpStatusCode 签名——
     * 触发限流时 NoSuchMethodError，block 响应无法生成 → 请求悬挂 30s 超时。
     * 仅注册 GatewayCallbackManager 不够：WebFlux 适配器走独立的 WebFluxCallbackManager。
     */
    private void initBlockHandler() {
        // Gateway 适配器（SentinelGatewayFilter 触发）
        GatewayCallbackManager.setBlockHandler((exchange, t) -> {
            log.info("[Gateway-Sentinel] 请求被限流, path={}",
                    exchange.getRequest().getURI().getPath());

            byte[] bytes = buildBlockResponse();
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Mono.just(bytes), byte[].class);
        });

        // WebFlux 适配器（T-106：SentinelWebFluxFilter + SentinelBlockExceptionHandler 触发）
        // 使用 HttpStatusCode 重载避免 NoSuchMethodError（Spring 6.x 兼容）
        com.alibaba.csp.sentinel.adapter.spring.webflux.callback.WebFluxCallbackManager
                .setBlockHandler((exchange, t) -> {
                    log.info("[Gateway-Sentinel-WebFlux] 请求被限流, path={}",
                            exchange.getRequest().getURI().getPath());

                    byte[] bytes = buildBlockResponse();
                    return ServerResponse.status(
                                    org.springframework.http.HttpStatusCode.valueOf(429))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Mono.just(bytes), byte[].class);
                });
    }

    /**
     * 构造统一限流响应体（Gateway/WebFlux 两个适配器共用）
     */
    private byte[] buildBlockResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 429);
        result.put("message", "请求过于频繁，请稍后再试");
        result.put("data", null);
        try {
            return objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            return ("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
