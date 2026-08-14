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
        return fails;
    }

    /** 软断言：答案数字与工具证据数字一致性；返回未匹配数字集合（空=一致） */
    public Set<String> checkNumberConsistency(AgentRun run) {
        String answer = run.finalAnswer() == null ? "" : run.finalAnswer();
        Set<String> ansNums = extractNonPercentNumbers(answer);
        Set<String> evNums = new LinkedHashSet<>();
        run.registry().records().forEach(r -> evNums.addAll(extractAllNumbers(r.result())));
        Set<String> unmatched = new LinkedHashSet<>(ansNums);
        unmatched.removeAll(evNums);
        return unmatched;
    }

    /** 提取非百分比上下文数字（41.9% 这类派生比率、[ev_xxx] 证据引用跳过） */
    static Set<String> extractNonPercentNumbers(String text) {
        Set<String> nums = new LinkedHashSet<>();
        if (text == null) {
            return nums;
        }
        // 剥掉证据引用块 [ev_xxx]（其 id 数字非业务数字）
        String cleaned = text.replaceAll("\\[[^\\]]*\\]", " ");
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
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            nums.add(m.group());
        }
        return nums;
    }
}
