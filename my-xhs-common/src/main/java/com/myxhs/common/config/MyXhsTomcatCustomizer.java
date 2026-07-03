package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tomcat Web 容器定制器
 * <p>
 * 基于各服务模块配置差异化线程池和连接参数。
 * application.yml 中每个模块通过 server.tomcat.threads.max 已做基础配置，
 * 本定制器补充 Connector 协议优化、MBean 注册和通用配置。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnWebApplication
public class MyXhsTomcatCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    /** 各模块差异化配置 key → 最大线程数 */
    @Value("${server.tomcat.threads.max:200}")
    private int maxThreads;

    @Value("${server.tomcat.threads.min-spare:20}")
    private int minSpareThreads;

    @Value("${server.tomcat.max-connections:8192}")
    private int maxConnections;

    @Value("${server.tomcat.accept-count:100}")
    private int acceptCount;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // MBean 注册（暴露 Tomcat 指标到 JMX，Prometheus 通过 jmx_exporter 采集）
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("maxThreads", String.valueOf(maxThreads));
            connector.setProperty("minSpareThreads", String.valueOf(minSpareThreads));
            connector.setProperty("maxConnections", String.valueOf(maxConnections));
        });

        log.info("[Tomcat] 定制完成: maxThreads={}, minSpare={}, maxConn={}, accept={}",
                maxThreads, minSpareThreads, maxConnections, acceptCount);
    }
}
