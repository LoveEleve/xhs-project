package com.myxhs.ai.app.config;

import com.myxhs.ai.tools.DlqRedeliverAccess;
import com.myxhs.ai.tools.DlqRedeliverTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M11 HITL：dlq.redeliver 受控执行装配（管理通道 URL 配置化）。
 * 未配置 → 工具返回 ERROR（不假装执行），与 log.search 白名单未配置全拒同模式。
 */
@Configuration
public class DlqRedeliverConfig {

    @Bean
    public DlqRedeliverAccess dlqRedeliverAccess(
            @Value("${myxhs.ai.hitl.dlq-redeliver.url:}") String url) {
        return new DlqRedeliverTool(url);
    }
}
