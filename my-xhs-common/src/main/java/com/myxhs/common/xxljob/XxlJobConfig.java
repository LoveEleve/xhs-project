package com.myxhs.common.xxljob;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job Executor 自动配置
 * <p>
 * 条件装配：仅当 xxl.job.enabled=true 时才注册 XxlJobSpringExecutor Bean。
 * 未接入 XXL-Job 的微服务无需配置此属性，不会创建多余的 Bean。
 * </p>
 * <p>
 * 各微服务 application.yml 只需配置：
 * <pre>
 * xxl:
 *   job:
 *     enabled: true
 *     admin:
 *       addresses: http://127.0.0.1:18080/xxl-job-admin
 *     executor:
 *       appname: my-xhs-payment  # 各服务自定义
 *       port: 9999               # 各服务自定义
 *     accessToken: my-xhs-xxl-job-token-2026
 * </pre>
 * </p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "xxl.job.enabled", havingValue = "true")
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses:}")
    private String adminAddresses;

    @Value("${xxl.job.executor.appname:}")
    private String appname;

    @Value("${xxl.job.executor.port:9999}")
    private int port;

    @Value("${xxl.job.accessToken:}")
    private String accessToken;

    @Value("${xxl.job.executor.logpath:/data/applogs/xxl-job/jobhandler}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays:30}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info("[XXL-Job] 初始化Executor: appname={}, adminAddresses={}, port={}",
                appname, adminAddresses, port);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
