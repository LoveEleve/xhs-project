package com.myxhs.ai.app.eval;

import com.myxhs.ai.app.service.agent.harness.AgentRun;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测断言执行（M6）：硬断言（确定性）+ 软断言（数字一致性=幻觉率检测）。
 */
public class EvalAsserter {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 日期时间模式（证据 asOf/答案中的时间戳数字会污染一致性比对，先整体剥掉）：
     *  2026-08-15 / 2025年 / 8-15 / 07:42 / 07:42:53.123Z / T07:42:53。
     *  注意不用 \b 边界——JDK 的 \b 把中文当 word 字符（"在8" 之间无边界），须按形态直接匹配。 */
    private static final Pattern DATETIME = Pattern.compile(
            "(?<![0-9])(?:"
                    + "\\d{4}-(?:1[0-2]|0?[1-9])-(?:3[01]|[12]\\d|0?[1-9])(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z)?)?"
                    + "|\\d{4}-(?:1[0-2]|0?[1-9])"
                    + "|(?:1[0-2]|0?[1-9])-(?:3[01]|[12]\\d|0?[1-9])"
                    + "|\\d{4}\\s*年(?:\\s*(?:1[0-2]|0?[1-9])\\s*月(?:\\s*(?:3[01]|[12]\\d|0?[1-9])\\s*日)?)?"
                    + "|(?:1[0-2]|0?[1-9])\\s*月(?:\\s*(?:3[01]|[12]\\d|0?[1-9])\\s*日)?"
                    + "|\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z)?"
                    // traceId/请求 ID：32 位 hex 非业务数字（M14 抽样暴露：traceId 查询用例误报幻觉）
                    + "|[0-9a-fA-F]{32}"
                    + ")(?!\\d)");


    /** 逐条执行硬断言；返回未通过的断言描述（空=硬断言全过） */
    public List<String> checkHard(EvalCase c, AgentRun run) {
        List<String> fails = new ArrayList<>();
        if (!c.statusIn().isEmpty() && !c.statusIn().contains(run.status().name())) {
            fails.add("status=" + run.status() + " 不在允许集 " + c.statusIn());
        }
        if (run.evidenceChain().size() < c.minEvidence()) {
            fails.add("证据数=" + run.evidenceChain().size() + " < 要求 " + c.minEvidence());
        }
        String answer = run.finalAnswer() == null ? "" : run.finalAnswer();
        for (String k : c.contains()) {
            if (!answer.contains(k)) {
                fails.add("答案缺少关键词: " + k);
            }
        }
        if (!c.anyContains().isEmpty()
                && c.anyContains().stream().noneMatch(answer::contains)) {
            fails.add("答案未命中任一关键词: " + c.anyContains());
        }
        for (String k : c.notContains()) {
            if (answer.contains(k)) {
                fails.add("答案含禁用词: " + k);
            }
        }
        for (String r : c.notRegex()) {
            if (Pattern.compile(r).matcher(answer).find()) {
                fails.add("答案匹配禁用模式: " + r);
            }
        }
        return fails;
    }

    /** 软断言：答案数字与工具证据数字一致性；返回未匹配数字集合（空=一致）。
     *  数值语义比对（修复误报）：工具返回 67.0 而模型写 67 视为一致（舍入）；日期时间数字不参与。 */
    public Set<String> checkNumberConsistency(AgentRun run) {
        String answer = run.finalAnswer() == null ? "" : run.finalAnswer();
        Set<String> ansNums = extractNonPercentNumbers(answer);
        Set<Double> evValues = new LinkedHashSet<>();
        run.registry().records().forEach(r -> evValues.addAll(toValues(extractAllNumbers(r.result()))));
        long nonIntegerEvidence = evValues.stream().filter(v -> !isIntegerLike(v)).count();
        Set<String> unmatched = new LinkedHashSet<>();
        for (String a : ansNums) {
            Double av = parse(a);
            if (av == null) {
                continue;
            }
            boolean matched = evValues.stream().anyMatch(ev -> approxEquals(av, ev, nonIntegerEvidence));
            if (!matched) {
                unmatched.add(a);
            }
        }
        return unmatched;
    }

    /** 数值一致性容差：直接近似（±0.5 或 ±10%）+ 秒/毫秒换算；
     *  推导值容忍仅覆盖同量级的非整数结果，避免 9→10000 这类编造被量级规则吞掉。 */
    static boolean approxEquals(double a, double b) {
        return approxEquals(a, b, 0);
    }

    static boolean approxEquals(double a, double b, long nonIntegerEvidence) {
        if (a == b) {
            return true;
        }
        if (approxEqualsRaw(a, b)) {
            return true;
        }
        if (approxEqualsUnit(a * 1000.0, b) || approxEqualsUnit(a, b * 1000.0)) {
            return true;
        }
        return approxEqualsDerived(a, b, nonIntegerEvidence);
    }

    private static boolean approxEqualsRaw(double a, double b) {
        double diff = Math.abs(a - b);
        if (diff <= 0.5) {
            return true;
        }
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return scale > 0 && diff <= 0.10 * scale;
    }

    private static boolean approxEqualsUnit(double a, double b) {
        double diff = Math.abs(a - b);
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return diff <= 0.5 || (scale > 0 && diff <= 0.01 * scale);
    }

    private static boolean approxEqualsDerived(double a, double b, long nonIntegerEvidence) {
        if (a <= 0 || b <= 0) {
            return false;
        }
        boolean anyNonInteger = !isIntegerLike(a) || !isIntegerLike(b);
        if (!anyNonInteger || nonIntegerEvidence < 2) {
            return false;
        }
        double min = Math.min(a, b);
        double max = Math.max(a, b);
        return max / min <= 3.0;
    }

    private static boolean isIntegerLike(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9;
    }

    static Double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Set<Double> toValues(Set<String> nums) {
        Set<Double> values = new LinkedHashSet<>();
        for (String n : nums) {
            Double v = parse(n);
            if (v != null) {
                values.add(v);
            }
        }
        return values;
    }

    /** 提取非百分比上下文数字（41.9% 这类派生比率、[ev_xxx] 证据引用、日期时间戳跳过） */
    static Set<String> extractNonPercentNumbers(String text) {
        Set<String> nums = new LinkedHashSet<>();
        if (text == null) {
            return nums;
        }
        // 剥掉证据引用块 [ev_xxx]（其 id 数字非业务数字），含裸引用（模型可能写"（ev_xxx）"）
        String cleaned = text.replaceAll("\\[[^\\]]*\\]|\\bev_[a-z0-9]+", " ");
        // 剥掉日期时间（asOf/时间戳/8-15 日期表达）
        cleaned = DATETIME.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\d{4}\\s*年\\s*(?:1[0-2]|0?[1-9])\\s*月(?:\\s*(?:3[01]|[12]\\d|0?[1-9])\\s*日)?", " ");
        cleaned = cleaned.replaceAll("\\d+\\s*天", " ");
        cleaned = cleaned.replaceAll("(?i)(?:top|排名)\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\|\\s*\\d+\\s*\\|", "| ");
        cleaned = cleaned.replaceAll("(?m)^\\s*[-*]?\\s*\\d+\\s*[|.)、]\\s*", " ");
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            // 枚举序号跳过（"1) xxx"、"2、xxx"、"3. xxx"）：数字后紧跟序号标点且后随空白/句末，
            // 否则模型用列表列举原因时序号会被误抽为业务数字（b2_mq_lag 实测误报幻觉）
            if (end < cleaned.length()) {
                char c = cleaned.charAt(end);
                if (c == ')' || c == '）' || c == '、' || c == '天') {
                    continue;
                }
                if (c == '.' && end + 1 < cleaned.length()
                        && Character.isWhitespace(cleaned.charAt(end + 1))) {
                    continue;
                }
            }
            boolean percentBefore = start > 0 && cleaned.charAt(start - 1) == '%';
            boolean percentAfter = end < cleaned.length() && cleaned.charAt(end) == '%';
            if (!percentBefore && !percentAfter) {
                nums.add(m.group());
            }
        }
        return nums;
    }

    static Set<String> extractAllNumbers(String text) {
        Set<String> nums = new LinkedHashSet<>();
        if (text == null) {
            return nums;
        }
        String cleaned = DATETIME.matcher(text).replaceAll(" ");
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            nums.add(m.group());
        }
        return nums;
    }
}
