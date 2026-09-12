package com.myxhs.ai.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.web.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 审计（只追加，M2.0）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9]{8,}|ark-[A-Za-z0-9-]{8,}|glsa_[A-Za-z0-9]{8,}|Bearer\\s+[A-Za-z0-9._-]{8,})");
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i)(password|passwd|secret|token|api[-_]?key)");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void record(Long actor, String action, String target, Map<String, Object> params, String result) {
        record(actor, action, target, params, result, MDC.get(TraceIdFilter.MDC_KEY));
    }

    public void record(Long actor, String action, String target, Map<String, Object> params, String result,
                       String traceId) {
        try {
            String paramsJson = params == null ? null
                    : objectMapper.writeValueAsString(sanitize(params));
            jdbcTemplate.update(
                    "INSERT INTO ai_audit(trace_id, actor, action, target, params, result) VALUES(?,?,?,?,?,?)",
                    traceId, actor, action, target, paramsJson, result);
        } catch (Exception e) {
            log.warn("[审计] 写入失败 action={}, err={}", action, e.getMessage());
        }
    }

    /** 脱敏：敏感键值置 ***，字符串中的密钥形态替换 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> sanitize(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((k, v) -> {
            if (SENSITIVE_KEY.matcher(k).find()) {
                out.put(k, "***");
            } else if (v instanceof Map<?, ?> nested) {
                out.put(k, sanitize((Map<String, Object>) nested));
            } else if (v instanceof String s) {
                out.put(k, SECRET.matcher(s).replaceAll("***"));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }
}
