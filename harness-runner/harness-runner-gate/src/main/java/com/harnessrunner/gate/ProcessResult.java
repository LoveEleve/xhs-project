package com.harnessrunner.gate;

public record ProcessResult(
        String command,
        int exitCode,
        boolean timedOut,
        long durationMs,
        String output,
        boolean outputTruncated,
        String outputRef) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
