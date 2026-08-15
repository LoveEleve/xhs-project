package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 受控日志检索访问（M9-1 受控进程工具的第一切片）：
 * 在白名单日志文件内检索 keyword（最近 N 行），纯 Java 读文件——无 shell、无命令注入面。
 * 安全模型：
 *  - service 必须命中白名单映射（不存在路径拼接，天然防路径遍历）
 *  - keyword 字符白名单正则 + 长度上限（防参数滥用）
 *  - tailLines 1~5000 上限；匹配行数/单行长度/总输出截断（防输出爆炸）
 * 实现：DirectLogSearchAccess（直读文件）/ McpLogBridge（经 MCP log.search）。
 */
public interface LogSearchAccess {

    @Tool("检索服务日志（白名单服务+最近 N 行内过滤 keyword；service 如 my-xhs-order / my-xhs-gateway / my-xhs-elasticsearch；" +
            "keyword 仅允许字母数字与常见符号，长度≤100）")
    String searchLog(@P("服务名（白名单），如 my-xhs-order") String service,
                     @P("检索关键词，如 ERROR / Deadlock / OutOfMemory") String keyword,
                     @P("最近多少行内检索（1~5000，默认 500）") String tailLines);
}
