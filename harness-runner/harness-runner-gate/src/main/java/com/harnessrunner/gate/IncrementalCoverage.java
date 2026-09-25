package com.harnessrunner.gate;

public record IncrementalCoverage(int executableChangedLines, int coveredChangedLines) {

    public boolean hasExecutableChanges() {
        return executableChangedLines > 0;
    }

    public double ratio() {
        return executableChangedLines == 0
                ? 0.0
                : (double) coveredChangedLines / executableChangedLines;
    }

    public double percent() {
        return ratio() * 100.0;
    }
}
