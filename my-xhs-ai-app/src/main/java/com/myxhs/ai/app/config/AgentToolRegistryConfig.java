package com.myxhs.ai.app.config;

import com.myxhs.ai.tools.AgentToolBinder;
import com.myxhs.ai.tools.LogSearchAccess;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
import com.myxhs.ai.tools.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M12 工具注册表装配（app 侧）：catalog 元数据 + 三桥执行器绑定（AgentToolBinder）。
 * 供 PolicyGuard 单测/工具清单审计引用；AgentHarness 构造器内部亦有同源装配（catalog 单一事实源）。
 */
@Configuration
public class AgentToolRegistryConfig {

    @Bean
    public ToolRegistry toolRegistry(MetricToolAccess metricToolAccess,
                                     ObsToolAccess obsToolAccess,
                                     LogSearchAccess logSearchAccess) {
        return AgentToolBinder.build(metricToolAccess, obsToolAccess, logSearchAccess);
    }
}
