package com.harnessrunner.gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnifiedDiffParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    public Map<String, List<LineRange>> parse(String diffOutput) {
        Map<String, List<LineRange>> changedLines = new LinkedHashMap<>();
        if (diffOutput == null || diffOutput.isBlank()) {
            return changedLines;
        }
        String currentPath = null;
        for (String line : diffOutput.split("\n")) {
            if (line.startsWith("+++ ")) {
                currentPath = newPath(line.substring(4).strip());
                if (currentPath != null) {
                    changedLines.computeIfAbsent(currentPath, key -> new ArrayList<>());
                }
            } else if (line.startsWith("@@") && currentPath != null) {
                Matcher matcher = HUNK.matcher(line);
                if (matcher.find()) {
                    int start = Integer.parseInt(matcher.group(1));
                    int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                    if (count > 0) {
                        changedLines.get(currentPath).add(new LineRange(start, start + count - 1));
                    }
                }
            }
        }
        return changedLines;
    }

    private static String newPath(String header) {
        if (header.equals("/dev/null")) {
            return null;
        }
        String path = header;
        if (path.startsWith("b/")) {
            path = path.substring(2);
        }
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        return path;
    }
}
