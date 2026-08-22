package com.myxhs.ai.app.service.knowledge;

import com.myxhs.ai.app.service.trace.TraceDiagnosisResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class CodeChangeInsightLoader {

    private static final File REPO_DIR = new File("/data/workspace/my-xhs");

    public Insight load(String filePath, String service, String primaryService, List<String> nextHops) {
        if (filePath == null || filePath.isBlank()) {
            return Insight.empty();
        }
        TraceDiagnosisResult.Owner owner = owner(filePath);
        List<String> recentCommits = recentCommits(filePath);
        String methodHint = detectMethodHint(filePath);
        MethodBlame methodBlame = methodBlame(filePath, methodHint);
        String methodSnippet = methodSnippet(filePath, methodBlame == null ? -1 : methodBlame.lineNo());
        String blameSummary = methodBlame == null
                ? (owner == null ? null : owner.name() + " 最近修改了 " + filePath + "（" + owner.commit() + "）")
                : methodBlame.author() + " 最近改动了 " + filePath + " 的 " + methodHint + "() 附近（" + methodBlame.commit() + "）";
        String changeExplanation = explain(service, primaryService, methodHint, nextHops, recentCommits, owner, methodBlame);
        String diagnosisTriplet = renderDiagnosisTriplet(filePath, methodHint, recentCommits);
        return new Insight(owner, recentCommits, blameSummary, changeExplanation, methodHint, diagnosisTriplet,
                methodBlame == null ? null : methodBlame.summary(), methodSnippet);
    }

    private List<String> recentCommits(String filePath) {
        try {
            Process p = new ProcessBuilder("git", "log", "--oneline", "-5", "--", filePath)
                    .directory(REPO_DIR)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0 || out.isBlank()) {
                return List.of();
            }
            return out.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private TraceDiagnosisResult.Owner owner(String filePath) {
        try {
            Process p = new ProcessBuilder("git", "log", "-1", "--pretty=format:%an|%ae|%h|%s", "--", filePath)
                    .directory(REPO_DIR)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() != 0 || out.isBlank()) {
                return null;
            }
            String[] parts = out.split("\\|", 4);
            if (parts.length < 4) {
                return null;
            }
            return new TraceDiagnosisResult.Owner(parts[0], parts[1], parts[2], parts[3]);
        } catch (Exception e) {
            return null;
        }
    }

    private String detectMethodHint(String filePath) {
        try {
            String text = java.nio.file.Files.readString(new File(REPO_DIR, filePath).toPath(), StandardCharsets.UTF_8);
            if (text.contains("preDeduct(")) {
                return "preDeduct";
            }
            if (text.contains("createOrder(") || text.contains("create(")) {
                return "createOrder";
            }
            if (text.contains("refund(")) {
                return "refund";
            }
            if (text.contains("pay(")) {
                return "pay";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private MethodBlame methodBlame(String filePath, String methodHint) {
        if (filePath == null || filePath.isBlank() || methodHint == null || methodHint.isBlank()) {
            return null;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(REPO_DIR, filePath).toPath(), StandardCharsets.UTF_8);
            int lineNo = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains(methodHint + "(") && (line.contains("public ") || line.contains("private ") || line.contains("protected "))) {
                    lineNo = i + 1;
                    break;
                }
            }
            if (lineNo < 0) {
                return null;
            }
            Process p = new ProcessBuilder("git", "blame", "-L", lineNo + "," + lineNo, "--porcelain", "--", filePath)
                    .directory(REPO_DIR)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0 || out.isBlank()) {
                return null;
            }
            String commit = out.lines().findFirst().map(s -> s.split(" ")[0]).orElse(null);
            String author = out.lines().filter(s -> s.startsWith("author ")).map(s -> s.substring("author ".length())).findFirst().orElse(null);
            String summary = out.lines().filter(s -> s.startsWith("summary ")).map(s -> s.substring("summary ".length())).findFirst().orElse(null);
            if (commit == null || author == null) {
                return null;
            }
            return new MethodBlame(lineNo, commit, author, summary);
        } catch (Exception e) {
            return null;
        }
    }

    private String methodSnippet(String filePath, int lineNo) {
        if (filePath == null || filePath.isBlank() || lineNo <= 0) {
            return null;
        }
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(REPO_DIR, filePath).toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lineNo - 1);
            int from = Math.max(0, start - 2);
            int to = Math.min(lines.size(), start + 1);
            int depth = 0;
            boolean seenBrace = false;
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                for (int j = 0; j < line.length(); j++) {
                    char ch = line.charAt(j);
                    if (ch == '{') {
                        depth++;
                        seenBrace = true;
                    } else if (ch == '}') {
                        depth--;
                    }
                }
                to = i + 1;
                if (seenBrace && depth <= 0) {
                    break;
                }
                if (to - from >= 40) {
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                sb.append(String.format("%4d  %s%n", i + 1, lines.get(i)));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    public static String renderDiagnosisTriplet(String filePath, String methodHint, List<String> recentCommits) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("优先检查：文件=").append(filePath);
        if (methodHint != null && !methodHint.isBlank()) {
            sb.append("；方法=").append(methodHint).append("()");
        }
        if (recentCommits != null && !recentCommits.isEmpty()) {
            sb.append("；最近提交=").append(recentCommits.get(0));
        }
        return sb.toString();
    }

    private String explain(String service, String primaryService, String methodHint, List<String> nextHops,
                           List<String> recentCommits, TraceDiagnosisResult.Owner owner, MethodBlame methodBlame) {
        if (recentCommits.isEmpty() && owner == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("变更解释：");
        if (primaryService != null && !primaryService.isBlank()) {
            sb.append("当前问题首先落在 ").append(primaryService);
            if (methodHint != null) {
                sb.append(".").append(methodHint).append("()");
            }
        } else if (service != null && !service.isBlank()) {
            sb.append("当前问题首先落在 ").append(service).append(" 服务");
        } else {
            sb.append("当前问题首先落在这段代码");
        }
        if (methodBlame != null) {
            sb.append("；该方法附近最近 blame 作者是 ").append(methodBlame.author()).append("（").append(methodBlame.commit()).append("）");
        } else if (owner != null) {
            sb.append("；最近提交者是 ").append(owner.name()).append("（").append(owner.commit()).append("）");
        }
        if (!recentCommits.isEmpty()) {
            sb.append("；最近一次改动是 `").append(recentCommits.get(0)).append("`");
        }
        if (nextHops != null && !nextHops.isEmpty()) {
            sb.append("；它的下一跳/相关协作点包括 ").append(String.join("、", nextHops.stream().limit(3).toList()));
        }
        sb.append("。排查时优先从最新改动和这些协作点交界处入手。");
        return sb.toString();
    }

    public record Insight(
            TraceDiagnosisResult.Owner owner,
            List<String> recentCommits,
            String blameSummary,
            String changeExplanation,
            String methodHint,
            String diagnosisTriplet,
            String methodBlameSummary,
            String methodSnippet) {
        static Insight empty() {
            return new Insight(null, List.of(), null, null, null, null, null, null);
        }
    }

    private record MethodBlame(int lineNo, String commit, String author, String summary) {
    }
}
