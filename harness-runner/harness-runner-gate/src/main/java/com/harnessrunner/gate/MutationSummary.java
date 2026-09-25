package com.harnessrunner.gate;

public record MutationSummary(int killed, int survived, int noCoverage) {

    public int total() {
        return killed + survived + noCoverage;
    }

    public boolean hasData() {
        return total() > 0;
    }

    public double score() {
        return total() == 0 ? 0.0 : (double) killed / total();
    }

    public double percent() {
        return score() * 100.0;
    }
}
