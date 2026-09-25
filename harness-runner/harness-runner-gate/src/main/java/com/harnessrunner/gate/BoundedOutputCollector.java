package com.harnessrunner.gate;

final class BoundedOutputCollector {

    private final int maxChars;
    private final StringBuilder tail = new StringBuilder();
    private long totalChars;
    private boolean truncated;

    BoundedOutputCollector(int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars 必须为正: " + maxChars);
        }
        this.maxChars = maxChars;
    }

    synchronized void append(char[] chars, int length) {
        totalChars += length;
        tail.append(chars, 0, length);
        if (tail.length() > maxChars) {
            tail.delete(0, tail.length() - maxChars);
            truncated = true;
        }
    }

    synchronized String text() {
        if (!truncated) {
            return tail.toString();
        }
        long omitted = totalChars - maxChars;
        return "[输出已截断：省略前 " + omitted + " 字符，保留末尾 " + maxChars + " 字符]\n" + tail;
    }

    synchronized boolean truncated() {
        return truncated;
    }

    synchronized long totalChars() {
        return totalChars;
    }
}
