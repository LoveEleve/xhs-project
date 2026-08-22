package com.myxhs.ai.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 受控日志检索实现（M9-1）：白名单文件内按 keyword 过滤最近 N 行。
 * 安全：
 *  - service → 文件路径严格 map 查找（配置注入，不存在路径拼接 → 无路径遍历）
 *  - keyword 白名单正则 + 长度上限
 *  - tailLines/匹配行数/单行长度/总输出 4 重截断
 * 返回 JSON 风格（与现有工具一致：status/tool/字段 + 抽样行）。
 */
public class DirectLogSearchAccess implements LogSearchAccess {

    private static final Logger log = LoggerFactory.getLogger(DirectLogSearchAccess.class);

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-.:/\\[\\]{}() ,=#@$%^&*+~'\"]{1,100}$");
    private static final int MAX_TAIL_LINES = 5000;
    private static final int DEFAULT_TAIL_LINES = 500;
    private static final int MAX_MATCH_LINES = 20;
    private static final int MAX_LINE_LEN = 500;
    private static final int MAX_TOTAL_LEN = 8000;
    private static final long LARGE_FILE_THRESHOLD_BYTES = 64L * 1024 * 1024;
    private static final int LARGE_FILE_READ_BYTES = 2 * 1024 * 1024;
    private static final int REVERSE_READ_BLOCK_BYTES = 64 * 1024;

    private final Map<String, String> fileWhitelist;

    /** @param fileWhitelist service → 日志文件绝对路径（白名单；仅允许配置内列出的服务） */
    public DirectLogSearchAccess(Map<String, String> fileWhitelist) {
        this.fileWhitelist = fileWhitelist == null ? Map.of() : fileWhitelist;
    }

    @Override
    public java.util.List<String> services() {
        return new java.util.ArrayList<>(fileWhitelist.keySet());
    }

    @Override
    public String searchLog(String service, String keyword, String tailLines) {
        String path = fileWhitelist.get(service == null ? "" : service.trim());
        if (path == null) {
            return "{\"status\":\"error\",\"tool\":\"log.search\",\"message\":\"服务不在白名单: " + service
                    + "（允许: " + fileWhitelist.keySet() + "）\"}";
        }
        String invalid = validateKeyword(keyword);
        if (invalid != null) {
            return "{\"status\":\"error\",\"tool\":\"log.search\",\"message\":\"" + invalid + "\"}";
        }
        int tail = parseTailLines(tailLines);
        File f = new File(path);
        if (!f.isFile()) {
            return "{\"status\":\"error\",\"tool\":\"log.search\",\"message\":\"日志文件不存在: " + path + "\"}";
        }
        try {
            List<String> lines = tailLinesOf(f, tail);
            List<String> matches = new ArrayList<>(Math.min(MAX_MATCH_LINES, lines.size()));
            for (int i = lines.size() - 1; i >= 0 && matches.size() < MAX_MATCH_LINES; i--) {
                String line = lines.get(i);
                if (line.contains(keyword.trim())) {
                    matches.add(line.length() > MAX_LINE_LEN ? line.substring(0, MAX_LINE_LEN) + "…" : line);
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"ok\",\"tool\":\"log.search\",\"service\":\"").append(service.trim())
                    .append("\",\"keyword\":\"").append(keyword.trim())
                    .append("\",\"scannedLines\":").append(lines.size())
                    .append(",\"matches\":").append(matches.size())
                    .append(",\"lines\":[");
            int total = 0;
            for (int i = 0; i < matches.size(); i++) {
                String line = matches.get(i);
                if (total > MAX_TOTAL_LEN) {
                    sb.append("\"...(截断)\"");
                    break;
                }
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(line)).append('"');
                total += line.length();
            }
            sb.append("]}");
            log.info("[log.search] service={} keyword={} scanned={} matches={}", service.trim(),
                    keyword.trim(), lines.size(), matches.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("[log.search] 读取失败 service={} err={}", service, e.getMessage());
            return "{\"status\":\"error\",\"tool\":\"log.search\",\"message\":\"读取日志失败: " + e.getMessage() + "\"}";
        }
    }

    public static String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "keyword 必填（检索关键词）";
        }
        String k = keyword.trim();
        if (!KEYWORD_PATTERN.matcher(k).matches()) {
            return "keyword 仅允许字母数字与常见符号（. - / : [ ] { } ( ) 空格等），长度≤100";
        }
        return null;
    }

    public static int parseTailLines(String tailLines) {
        try {
            int n = Integer.parseInt(tailLines == null || tailLines.isBlank() ? "500" : tailLines.trim());
            if (n < 1) {
                return DEFAULT_TAIL_LINES;
            }
            return Math.min(n, MAX_TAIL_LINES);
        } catch (NumberFormatException e) {
            return DEFAULT_TAIL_LINES;
        }
    }

    private static List<String> tailLinesOf(File f, int tail) throws Exception {
        if (f.length() > LARGE_FILE_THRESHOLD_BYTES) {
            return tailLinesOfLargeFile(f, tail);
        }
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (lines.size() == tail) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
        }
        return new ArrayList<>(lines);
    }

    private static List<String> tailLinesOfLargeFile(File f, int tail) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long fileLength = raf.length();
            long lowerBound = Math.max(0L, fileLength - LARGE_FILE_READ_BYTES);
            long position = fileLength;
            byte[] block = new byte[REVERSE_READ_BLOCK_BYTES];
            java.io.ByteArrayOutputStream reversed = new java.io.ByteArrayOutputStream();
            int newlineCount = 0;
            while (position > lowerBound && newlineCount <= tail + 1) {
                int readSize = (int) Math.min(block.length, position - lowerBound);
                position -= readSize;
                raf.seek(position);
                raf.readFully(block, 0, readSize);
                for (int i = readSize - 1; i >= 0; i--) {
                    byte b = block[i];
                    reversed.write(b);
                    if (b == '\n') {
                        newlineCount++;
                        if (newlineCount > tail + 1) {
                            break;
                        }
                    }
                }
            }
            byte[] raw = reversed.toByteArray();
            for (int i = 0, j = raw.length - 1; i < j; i++, j--) {
                byte tmp = raw[i];
                raw[i] = raw[j];
                raw[j] = tmp;
            }
            String text = new String(raw, StandardCharsets.UTF_8);
            String[] split = text.split("\\R");
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            for (String line : split) {
                if (line.isEmpty()) {
                    continue;
                }
                if (lines.size() == tail) {
                    lines.removeFirst();
                }
                lines.addLast(line);
            }
            return new ArrayList<>(lines);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
