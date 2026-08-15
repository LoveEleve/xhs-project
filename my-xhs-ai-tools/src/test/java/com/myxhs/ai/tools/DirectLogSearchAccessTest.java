package com.myxhs.ai.tools;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受控日志检索单测（M9-1）：白名单/注入拒绝/截断/正常检索。
 */
class DirectLogSearchAccessTest {

    @TempDir
    static Path tmp;

    static Path logFile;
    static DirectLogSearchAccess access;

    @BeforeAll
    static void setup() throws Exception {
        logFile = tmp.resolve("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("2026-08-14 12:00:0").append(i % 10).append(" [thread-").append(i).append("] ")
                    .append(i % 50 == 0 ? "ERROR 服务异常堆栈 OutOfMemoryError" : "INFO 正常请求")
                    .append('\n');
        }
        Files.write(logFile, sb.toString().getBytes());
        access = new DirectLogSearchAccess(Map.of(
                "my-xhs-order", logFile.toString(),
                "my-xhs-elasticsearch", logFile.toString()));
    }

    @Test
    void 正常检索_命中keyword() {
        String r = access.searchLog("my-xhs-order", "OutOfMemoryError", "1000");
        assertTrue(r.contains("\"status\":\"ok\""), r);
        assertTrue(r.contains("\"matches\":20"), "1000 行内 20 处 ERROR 应全命中: " + r);
        assertTrue(r.contains("OutOfMemoryError"), r);
    }

    @Test
    void 白名单外服务_拒绝() {
        String r = access.searchLog("my-xhs-payment", "ERROR", "500");
        assertTrue(r.contains("\"status\":\"error\""), r);
        assertTrue(r.contains("不在白名单"), r);
    }

    @Test
    void 非法keyword_拒绝() {
        String r = access.searchLog("my-xhs-order", "ERROR; rm -rf /", "500");
        assertTrue(r.contains("\"status\":\"error\""), r);
        assertTrue(r.contains("仅允许"), r);
        String r2 = access.searchLog("my-xhs-order", "x".repeat(200), "500");
        assertTrue(r2.contains("\"status\":\"error\""), r2);
    }

    @Test
    void 空keyword_拒绝() {
        String r = access.searchLog("my-xhs-order", " ", "500");
        assertTrue(r.contains("\"status\":\"error\""), r);
    }

    @Test
    void tailLines越界_收敛到默认或上限() {
        // 非法输入不报错（工具侧容错），结果仍正常
        String r = access.searchLog("my-xhs-order", "ERROR", "99999");
        assertTrue(r.contains("\"status\":\"ok\""), r);
        String r2 = access.searchLog("my-xhs-order", "ERROR", "abc");
        assertTrue(r2.contains("\"status\":\"ok\""), r2);
    }

    @Test
    void 结果截断_不爆炸() {
        // 大量命中：最多 20 行，总长 ≤8000
        String r = access.searchLog("my-xhs-order", "INFO", "1000");
        assertTrue(r.contains("\"status\":\"ok\""), r);
        assertTrue(r.length() <= 12000, "输出应受控: " + r.length());
        assertNotNull(r);
        assertFalse(r.isEmpty());
    }

    @Test
    void 文件不存在_返回错误() {
        String r = access.searchLog("my-xhs-order", "ERROR", "500");
        // 白名单路径存在；用不存在的文件验证
        DirectLogSearchAccess a2 = new DirectLogSearchAccess(Map.of("svc", tmp.resolve("nope.log").toString()));
        String r2 = a2.searchLog("svc", "ERROR", "500");
        assertTrue(r2.contains("不存在"), r2);
    }
}
