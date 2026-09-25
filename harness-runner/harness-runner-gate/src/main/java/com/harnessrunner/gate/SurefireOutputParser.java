package com.harnessrunner.gate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SurefireOutputParser {

    private static final Pattern SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    public Optional<TestSummary> parse(String output) {
        if (output == null || output.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = SUMMARY.matcher(output);
        TestSummary last = null;
        while (matcher.find()) {
            last = new TestSummary(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4)));
        }
        return Optional.ofNullable(last);
    }
}
