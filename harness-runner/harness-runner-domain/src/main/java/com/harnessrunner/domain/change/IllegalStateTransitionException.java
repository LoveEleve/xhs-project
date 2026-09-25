package com.harnessrunner.domain.change;

public class IllegalStateTransitionException extends RuntimeException {

    private final ChangeStatus from;
    private final ChangeStatus to;

    public IllegalStateTransitionException(ChangeStatus from, ChangeStatus to) {
        super("非法的状态转移: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public ChangeStatus from() {
        return from;
    }

    public ChangeStatus to() {
        return to;
    }
}
