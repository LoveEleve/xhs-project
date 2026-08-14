package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 观测工具访问接口（D4 B3 面：L2 只读观测）。
 * 实现：PrometheusObsAccess（直连 Prometheus）/ McpObsBridge（经 MCP service.http_*）。
 * 窗口语义：最近 N 小时（观测时间序列），service 空=全部服务。
 */
public interface ObsToolAccess {

    @Tool("查询服务 HTTP 5xx 错误统计（按 uri 聚合，最近 N 小时；service 如 my-xhs-gateway，空=全部；hours 1~168）")
    String httpErrors(@P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
                      @P("最近小时数（1~168）") String hours);

    @Tool("查询服务 HTTP 慢端点 top（P95 延迟秒，最近 N 小时；service 如 my-xhs-order，空=全部）")
    String httpLatency(@P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
                       @P("最近小时数（1~168）") String hours);
}
