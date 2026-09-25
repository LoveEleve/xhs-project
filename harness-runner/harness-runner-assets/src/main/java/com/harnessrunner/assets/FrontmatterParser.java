package com.harnessrunner.assets;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FrontmatterParser {

    private static final String FENCE = "---";

    public Optional<Frontmatter> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Optional.empty();
        }
        String[] lines = markdown.split("\n", -1);
        if (!lines[0].strip().equals(FENCE)) {
            return Optional.empty();
        }
        int closing = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals(FENCE)) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            return Optional.empty();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        for (int i = 1; i < closing; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            attributes.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }

        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, closing + 1, lines.length));
        return Optional.of(new Frontmatter(attributes, body));
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
