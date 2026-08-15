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
            "\\d{4}-\\d{1,2}-\\d{1,2}(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z)?)?"
                    + "|\\d{4}-\\d{1,2}(?!-\\d)"
                    + "|(?<!\\d)\\d{1,2}-\\d{1,2}(?!\\d)"
                    + "|\\d{4}年(?:\\d{1,2}月(?:\\d{1,2}日)?)?"
                    + "|\\d{1,2}月(?:\\d{1,2}日)?"
                    + "|\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z)?");

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
        Set<String> unmatched = new LinkedHashSet<>();
        for (String a : ansNums) {
            Double av = parse(a);
            if (av == null) {
                continue;
            }
            boolean matched = evValues.stream().anyMatch(ev -> approxEquals(av, ev));
            if (!matched) {
                unmatched.add(a);
            }
        }
        return unmatched;
    }

    /** 数值一致性容差：直接近似（±0.5 或 ±10%）或量级窗口（证据的 0.5x~3x，覆盖模型
     *  推导值如 65.1-21.1=44；编造的数量级跳变如 46→10000 仍检出） */
    static boolean approxEquals(double a, double b) {
        if (a == b) {
            return true;
        }
        double diff = Math.abs(a - b);
        if (diff <= 0.5) {
            return true;
        }
        double scale = Math.max(Math.abs(a), Math.abs(b));
        if (scale > 0 && diff <= 0.10 * scale) {
            return true;
        }
        if (a > 0 && b > 0) {
            double ratio = Math.max(a, b) / Math.min(a, b);
            return ratio <= 3.0;
        }
        return false;
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
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
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
