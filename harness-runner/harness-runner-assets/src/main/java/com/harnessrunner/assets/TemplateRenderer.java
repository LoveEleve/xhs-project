package com.harnessrunner.assets;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    public String render(String template, Map<String, String> context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = context.get(matcher.group(1));
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
