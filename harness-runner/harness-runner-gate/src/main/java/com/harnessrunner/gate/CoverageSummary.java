package com.harnessrunner.gate;

public record CoverageSummary(int lineCovered, int lineMissed, int branchCovered, int branchMissed) {

    public boolean hasBranchData() {
        return branchCovered + branchMissed > 0;
    }

    public boolean hasLineData() {
        return lineCovered + lineMissed > 0;
    }

    public double lineRatio() {
        return ratio(lineCovered, lineMissed);
    }

    public double branchRatio() {
        return ratio(branchCovered, branchMissed);
    }

    public double linePercent() {
        return lineRatio() * 100.0;
    }

    public double branchPercent() {
        return branchRatio() * 100.0;
    }

    private static double ratio(int covered, int missed) {
        int total = covered + missed;
        return total == 0 ? 0.0 : (double) covered / total;
    }
}
