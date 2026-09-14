package com.myxhs.ai.config;

import com.myxhs.ai.agent.AgentService;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 子进程健康自愈（RV19/F16）。
 *
 * <p>检测信号=JVM 直接子进程命令行标记，连续 2 次缺失判定死亡。经实测（RV19）：
 * AgentScope 运行时摘除/重挂 MCP client 后，已注册工具不会重绑新 client
 * （调用报 "MCP client not initialized"），因此采用**进程级自愈**：
 * 默认**仅告警不重启**（self-restart-enabled=false）：实测 npm exec 包装的进程链存在
 * 命令行探测抖动，误判会引发重启风暴（RV19 复盘，NRestarts=24）。启用时确认死亡即
 * fail-fast 退出，由 systemd（Restart=always）拉起，启动路径全量重建 MCP client 与工具绑定；
 * 带限流（默认每小时最多 3 次），超限仅告警不退出，避免重启风暴。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpHealthMonitor {

    private final AgentService agentService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @org.springframework.beans.factory.annotation.Value("${myxhs.mcp.self-restart-enabled:false}")
    private boolean selfRestartEnabled;

    @org.springframework.beans.factory.annotation.Value("${myxhs.mcp.self-restart-max-per-hour:3}")
    private int maxSelfRestartPerHour;

    private final Deque<Long> selfRestartTimestamps = new ArrayDeque<>();

    /** 三连击防抖：进程树/命令行探测存在抖动，连续 3 次缺失才处置 */
    private final Map<String, Integer> missStreak = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${myxhs.mcp.health-interval-ms:30000}", initialDelay = 45000)
    public void checkAndHeal() {
        Toolkit toolkit = agentService.toolkit();
        if (toolkit == null) {
            return;
        }
        Map<String, McpServerConfig> configs = agentService.mcpConfigs();
        for (Map.Entry<String, McpServerConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            boolean alive = isProcessAlive(entry.getValue());
            if (meterRegistry != null) {
                meterRegistry.gauge("ai_mcp_health", java.util.List.of(io.micrometer.core.instrument.Tag.of("server", name)),
                        alive ? 1 : 0);
            }
            if (alive) {
                missStreak.remove(name);
                continue;
            }
            int miss = missStreak.merge(name, 1, Integer::sum);
            log.warn("[MCP自愈] server={} 子进程不存在（第 {} 次）", name, miss);
            if (miss < 3) {
                continue;
            }
            missStreak.put(name, 0);
            if (meterRegistry != null) {
                meterRegistry.counter("ai_mcp_restarts_total", "server", name).increment();
            }
            healByRestart(name);
        }
    }

    /** 进程级自愈：确认死亡后在限流内 fail-fast 退出，由 systemd 重启全量重建 */
    void healByRestart(String server) {
        if (!selfRestartEnabled) {
            log.error("[MCP自愈] server={} 已死亡且自愈关闭，工具不可用直到人工重启（myxhs.mcp.self-restart-enabled=false）", server);
            return;
        }
        synchronized (selfRestartTimestamps) {
            long oneHourAgo = System.currentTimeMillis() - 3600_000L;
            while (!selfRestartTimestamps.isEmpty() && selfRestartTimestamps.peekFirst() < oneHourAgo) {
                selfRestartTimestamps.pollFirst();
            }
            if (selfRestartTimestamps.size() >= maxSelfRestartPerHour) {
                log.error("[MCP自愈] server={} 死亡但重启限流已达 {}/h，停止自愈；请人工介入", server, maxSelfRestartPerHour);
                return;
            }
            selfRestartTimestamps.addLast(System.currentTimeMillis());
        }
        log.error("[MCP自愈] server={} 子进程确认死亡，fail-fast 退出等待 systemd 拉起（进程级自愈）", server);
        System.exit(70);
    }

    /** 存活判定：全进程扫描命令行标记（npm exec 包装退出后 node 会被 init 收养，仅扫子进程会误判） */
    boolean isProcessAlive(McpServerConfig config) {
        String marker = processMarker(config);
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .anyMatch(p -> p.info().commandLine().map(cl -> cl.contains(marker)).orElse(false));
    }

    /** 进程标记推导：优先取非 flag 长参数的可执行名（如 @elastic/mcp-server-elasticsearch@0.1.1），否则取 command basename */
    static String processMarker(McpServerConfig config) {
        if (config.getArgs() != null) {
            Optional<String> arg = config.getArgs().stream()
                    .filter(a -> a != null && !a.startsWith("-") && a.length() > 6)
                    .findFirst();
            if (arg.isPresent()) {
                String a = arg.get();
                String tail = a.substring(a.lastIndexOf('/') + 1);
                int at = tail.indexOf('@', 1);
                return at > 0 ? tail.substring(0, at) : tail;
            }
        }
        String command = config.getCommand() == null ? "" : config.getCommand();
        return command.substring(command.lastIndexOf('/') + 1);
    }
}
