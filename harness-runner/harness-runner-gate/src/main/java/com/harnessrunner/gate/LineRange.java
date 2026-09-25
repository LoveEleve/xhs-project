package com.harnessrunner.gate;

public record LineRange(int start, int end) {

    public LineRange {
        if (start < 1 || end < start) {
            throw new IllegalArgumentException("非法行区间: " + start + "-" + end);
        }
    }
}
