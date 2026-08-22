package com.myxhs.ai.app.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.myxhs.ai.app.service.trace.TraceDiagnosisResult;
import com.myxhs.ai.app.service.trace.TraceNavigationLoader;
import com.myxhs.ai.app.service.trace.TraceServiceContextLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CodeSearchService {

    private static final Path CODE_MAP_ROOT = Path.of("/data/workspace/my-xhs/my-xhs-ai/knowledge/code-map");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final TraceServiceContextLoader contextLoader;
    private final TraceNavigationLoader navigationLoader;
    private final CodeChangeInsightLoader changeInsightLoader;
    private final TraceSampleLinker traceSampleLinker;

    public CodeSearchService() {
        this(new TraceServiceContextLoader(), new TraceNavigationLoader(), new CodeChangeInsightLoader(), new TraceSampleLinker());
    }

    public CodeSearchService(TraceServiceContextLoader contextLoader, TraceNavigationLoader navigationLoader,
                             CodeChangeInsightLoader changeInsightLoader,
                             TraceSampleLinker traceSampleLinker) {
        this.contextLoader = contextLoader;
        this.navigationLoader = navigationLoader;
        this.changeInsightLoader = changeInsightLoader;
        this.traceSampleLinker = traceSampleLinker;
    }

    public String search(String question) {
        CodeSearchResult result = searchStructured(question);
        return result == null ? null : result.summary();
    }

    public CodeSearchResult searchStructured(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.toLowerCase(Locale.ROOT);
        List<String> tokens = extractTokens(q);
        String simple = extractServiceName(q);
        List<ScoredHit> hits = new ArrayList<>();
        for (String subdir : List.of("service-map", "feign-map", "mq-map", "state-map", "call-chain-map")) {
            Path dir = CODE_MAP_ROOT.resolve(subdir);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var stream = Files.walk(dir, 1)) {
                stream.filter(p -> p.toString().endsWith(".yaml")).forEach(p -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = yaml.readValue(p.toFile(), Map.class);
                        int score = score(m, q, simple, tokens);
                        if (score > 0) {
                            hits.add(buildHit(question, m, score));
                        }
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        hits.sort(Comparator.comparingInt(ScoredHit::score).reversed());
        List<ScoredHit> ranked = hits.stream().distinct().limit(3).toList();
        List<CodeSearchResult.Hit> resultHits = ranked.stream().map(ScoredHit::structured).toList();
        String summary = "代码结构：\n\n" + String.join("\n\n---\n\n", resultHits.stream().map(CodeSearchResult.Hit::renderedText).toList());
        return new CodeSearchResult(question, summary, resultHits.get(0), resultHits);
    }

    private ScoredHit buildHit(String query, Map<String, Object> m, int score) {
        String service = str(m.get("service"));
        TraceServiceContextLoader.ServiceContext context = service == null || service.isBlank()
                ? null : contextLoader.load("my-xhs-" + service);
        TraceNavigationLoader.Navigation nav = service == null || service.isBlank()
                ? null : navigationLoader.load("my-xhs-" + service);
        String text = format(m, context, nav);
        CodeChangeInsightLoader.Insight insight = changeInsightLoader.load(
                nav != null && nav.primaryServicePath() != null ? nav.primaryServicePath() : firstSource(m),
                service, nav == null ? null : nav.primaryService(), nav == null ? List.of() : nav.nextHops());
        List<CodeSearchResult.RelatedTraceSample> relatedTraceSamples = service == null ? List.of()
                : traceSampleLinker.findRelated(service, insight.methodHint(), str(m.get("role")), list(m.get("responsibility")))
                .stream()
                .map(s -> new CodeSearchResult.RelatedTraceSample(s.id(), s.traceId(), s.route(), s.note()))
                .toList();
        CodeSearchResult.Hit hit = new CodeSearchResult.Hit(
                str(m.get("id")),
                service,
                str(m.get("role")),
                str(m.get("semantic_role")),
                str(m.get("question")),
                str(m.get("client_class")),
                str(m.get("caller_service")),
                str(m.get("callee_service")),
                firstSource(m),
                list(m.get("key_services")),
                list(m.get("key_controllers")),
                list(m.get("key_consumers")),
                list(m.get("key_feign_clients")),
                list(m.get("responsibility")),
                insight.owner(),
                insight.recentCommits(),
                insight.blameSummary(),
                insight.changeExplanation(),
                nav == null ? null : nav.primaryService(),
                nav == null ? null : nav.primaryServicePath(),
                nav == null ? null : nav.primaryServiceSnippet(),
                insight.methodHint(),
                insight.diagnosisTriplet(),
                insight.methodBlameSummary(),
                insight.methodSnippet(),
                relatedTraceSamples,
                nav == null ? List.of() : nav.nextHops(),
                score,
                text);
        return new ScoredHit(text, score, hit);
    }

    private int score(Map<String, Object> m, String q, String simple, List<String> tokens) {
        int score = 0;
        score += scoreString(m.get("question"), q, simple, tokens, 20, 8);
        score += scoreString(m.get("service"), q, simple, tokens, 18, 8);
        score += scoreString(m.get("role"), q, simple, tokens, 16, 6);
        score += scoreString(m.get("semantic_role"), q, simple, tokens, 16, 6);
        score += scoreString(m.get("description"), q, simple, tokens, 12, 5);
        score += scoreString(m.get("id"), q, simple, tokens, 10, 5);
        score += scoreString(m.get("edge_id"), q, simple, tokens, 10, 5);
        score += scoreString(m.get("client_class"), q, simple, tokens, 24, 10);
        score += scoreString(m.get("caller_service"), q, simple, tokens, 18, 8);
        score += scoreString(m.get("callee_service"), q, simple, tokens, 18, 8);
        score += scoreString(m.get("topic"), q, simple, tokens, 24, 10);
        score += scoreString(m.get("source"), q, simple, tokens, 14, 6);
        score += scoreList(m.get("responsibility"), q, simple, tokens, 12, 5);
        score += scoreList(m.get("components"), q, simple, tokens, 16, 8);
        score += scoreList(m.get("key_services"), q, simple, tokens, 18, 8);
        score += scoreList(m.get("key_controllers"), q, simple, tokens, 12, 5);
        score += scoreList(m.get("key_consumers"), q, simple, tokens, 18, 8);
        score += scoreList(m.get("key_feign_clients"), q, simple, tokens, 18, 8);
        score += scoreList(m.get("main_endpoints"), q, simple, tokens, 8, 4);
        score += scoreList(m.get("consumers"), q, simple, tokens, 24, 10);
        score += scoreList(m.get("producers"), q, simple, tokens, 12, 5);
        score += scoreList(m.get("sources"), q, simple, tokens, 16, 8);
        if (score == 0) {
            return 0;
        }
        String service = str(m.get("service"));
        if (simple != null && service != null && service.equalsIgnoreCase(simple)) {
            score += 15;
        }
        String question = str(m.get("question"));
        if (question != null && q.contains("哪个类") && question.contains("哪个核心类")) {
            score += 15;
        }
        if (containsAny(q, "consumer", "消费者")
                && containsAny(joinStrings(m.get("question"), m.get("semantic_role"), m.get("id"), m.get("topic"), m.get("answer"), m.get("consumers"), m.get("key_consumers"), m.get("sources")), "consumer", "消费者", "ordertransactionconsumer", "transactionconsumer")) {
            score += 24;
        }
        if (containsAny(q, "feign")
                && containsAny(joinStrings(m.get("client_class"), m.get("question"), m.get("source")), "feign")) {
            score += 20;
        }
        if (containsAny(q, "topic")
                && containsAny(joinStrings(m.get("question"), m.get("topic"), m.get("id")), "topic")) {
            score += 18;
        }
        if (containsAny(q, "order", "订单") && containsAny(joinStrings(m.get("caller_service"), m.get("question"), m.get("source")), "order", "订单")) {
            score += 10;
        }
        if (containsAny(q, "payment", "支付") && containsAny(joinStrings(m.get("callee_service"), m.get("question"), m.get("source")), "payment", "支付")) {
            score += 10;
        }
        return score;
    }

    private String format(Map<String, Object> m,
                          TraceServiceContextLoader.ServiceContext context,
                          TraceNavigationLoader.Navigation nav) {
        String id = str(m.get("id"));
        String question = str(m.get("question"));
        String role = str(m.get("role"));
        String semantic = str(m.get("semantic_role"));
        String service = str(m.get("service"));
        String edge = str(m.get("edge_id"));
        String client = str(m.get("client_class"));
        String caller = str(m.get("caller_service"));
        String callee = str(m.get("callee_service"));

        StringBuilder sb = new StringBuilder();
        if (id != null) {
            sb.append("名称：").append(id);
        }
        if (service != null) {
            sb.append("\n服务：").append(service);
        }
        if (role != null) {
            sb.append("\n职责：").append(role);
        }
        if (semantic != null) {
            sb.append("\n语义：").append(semantic);
        }
        if (question != null) {
            sb.append("\n说明：").append(question);
        }
        if (client != null) {
            sb.append("\nFeign 客户端：").append(client);
        }
        if (caller != null && callee != null) {
            sb.append("\n调用：").append(caller).append(" -> ").append(callee);
        }
        List<String> keyServices = list(m.get("key_services"));
        List<String> keyControllers = list(m.get("key_controllers"));
        List<String> keyConsumers = list(m.get("key_consumers"));
        List<String> keyFeign = list(m.get("key_feign_clients"));
        List<String> responsibilities = list(m.get("responsibility"));
        List<String> sources = list(m.get("sources"));
        String source = str(m.get("source"));

        if (!keyServices.isEmpty()) {
            sb.append("\n关键服务类：").append(String.join("、", keyServices));
        }
        if (!keyControllers.isEmpty()) {
            sb.append("\n关键控制器：").append(String.join("、", keyControllers));
        }
        if (!keyConsumers.isEmpty()) {
            sb.append("\n关键消费者：").append(String.join("、", keyConsumers));
        }
        if (!keyFeign.isEmpty()) {
            sb.append("\nFeign 客户端：").append(String.join("、", keyFeign));
        }
        if (!responsibilities.isEmpty()) {
            sb.append("\n职责：").append(String.join("、", responsibilities));
        }
        String prefix = "/data/workspace/my-xhs/";
        if (!sources.isEmpty()) {
            sb.append("\n源码落点：").append(sources.get(0).startsWith(prefix) ? sources.get(0).substring(prefix.length()) : sources.get(0));
        } else if (source != null) {
            sb.append("\n源码落点：").append(source.startsWith(prefix) ? source.substring(prefix.length()) : source);
        }
        if (context != null && context.owner() != null) {
            TraceDiagnosisResult.Owner owner = context.owner();
            sb.append("\n负责人：").append(owner.name()).append(" <").append(owner.email()).append(">")
                    .append(" @").append(owner.commit()).append(" ").append(owner.summary());
        }
        if (context != null && !context.recentCommits().isEmpty()) {
            sb.append("\n最近提交：").append(String.join(" | ", context.recentCommits()));
        }
        if (context != null) {
            CodeChangeInsightLoader.Insight insight = changeInsightLoader.load(
                    nav != null && nav.primaryServicePath() != null ? nav.primaryServicePath() : firstSource(m),
                    service, nav == null ? null : nav.primaryService(), nav == null ? List.of() : nav.nextHops());
            if (insight.methodBlameSummary() != null && !insight.methodBlameSummary().isBlank()) {
                sb.append("\n方法级 blame：").append(insight.methodBlameSummary());
            }
        }
        if (nav != null && nav.primaryService() != null) {
            sb.append("\n主服务类：").append(nav.primaryService());
        }
        if (nav != null && nav.primaryServicePath() != null) {
            sb.append("\n主服务源码：").append(nav.primaryServicePath());
        }
        if (nav != null && nav.nextHops() != null && !nav.nextHops().isEmpty()) {
            sb.append("\n下一跳：").append(String.join("、", nav.nextHops()));
        }
        return sb.toString();
    }

    private int scoreString(Object value, String q, String simple, List<String> tokens, int exactBoost, int tokenBoost) {
        if (!(value instanceof String s) || s.isBlank()) {
            return 0;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.contains(q)) {
            score += exactBoost;
        }
        if (simple != null && lower.contains(simple)) {
            score += 10;
        }
        for (String token : tokens) {
            if (lower.contains(token)) {
                score += tokenBoost;
            }
        }
        return score;
    }

    private int scoreList(Object value, String q, String simple, List<String> tokens, int exactBoost, int tokenBoost) {
        if (!(value instanceof List<?> list)) {
            return 0;
        }
        int score = 0;
        for (Object item : list) {
            score += scoreString(String.valueOf(item), q, simple, tokens, exactBoost, tokenBoost);
        }
        return score;
    }

    private static List<String> extractTokens(String q) {
        List<String> tokens = new ArrayList<>();
        for (String token : List.of("库存", "预扣", "支付", "退款", "订单", "topic", "consumer", "消费者", "feign", "inventory", "payment", "order", "prededuct")) {
            if (q.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String joinStrings(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            if (value != null) {
                sb.append(String.valueOf(value).toLowerCase(Locale.ROOT)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String extractServiceName(String q) {
        for (String s : List.of("订单", "库存", "支付", "优惠券", "用户", "搜索", "内容", "购物车", "网关", "通知", "home", "bff")) {
            if (q.contains(s)) {
                return switch (s) {
                    case "订单" -> "order";
                    case "库存" -> "inventory";
                    case "支付" -> "payment";
                    case "优惠券" -> "coupon";
                    case "用户" -> "user";
                    case "搜索" -> "search";
                    case "内容" -> "content";
                    case "购物车" -> "cart";
                    case "网关" -> "gateway";
                    case "通知" -> "notification";
                    case "home" -> "home";
                    case "bff" -> "home";
                    default -> null;
                };
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static String firstSource(Map<String, Object> m) {
        List<String> sources = list(m.get("sources"));
        if (!sources.isEmpty()) {
            String prefix = "/data/workspace/my-xhs/";
            return sources.get(0).startsWith(prefix) ? sources.get(0).substring(prefix.length()) : sources.get(0);
        }
        String source = str(m.get("source"));
        if (source == null) {
            return null;
        }
        String prefix = "/data/workspace/my-xhs/";
        return source.startsWith(prefix) ? source.substring(prefix.length()) : source;
    }

    private record ScoredHit(String text, int score, CodeSearchResult.Hit structured) {
    }
}
