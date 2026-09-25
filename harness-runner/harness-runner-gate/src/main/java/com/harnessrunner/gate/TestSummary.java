package com.harnessrunner.gate;

public record TestSummary(int testsRun, int failures, int errors, int skipped) {

    public int failed() {
        return failures + errors;
    }

    public boolean allPassed() {
        return failed() == 0;
    }
}
