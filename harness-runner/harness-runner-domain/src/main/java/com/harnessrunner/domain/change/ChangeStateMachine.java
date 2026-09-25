package com.harnessrunner.domain.change;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ChangeStateMachine {

    private static final Map<ChangeStatus, Set<ChangeStatus>> TRANSITIONS = new EnumMap<>(ChangeStatus.class);

    static {
        TRANSITIONS.put(ChangeStatus.CREATED,
                EnumSet.of(ChangeStatus.IN_PROGRESS, ChangeStatus.CANCELLED));
        TRANSITIONS.put(ChangeStatus.IN_PROGRESS,
                EnumSet.of(ChangeStatus.IN_PROGRESS, ChangeStatus.AWAITING_APPROVAL,
                        ChangeStatus.PAUSED, ChangeStatus.FAILED,
                        ChangeStatus.DONE, ChangeStatus.CANCELLED));
        TRANSITIONS.put(ChangeStatus.AWAITING_APPROVAL,
                EnumSet.of(ChangeStatus.IN_PROGRESS, ChangeStatus.CANCELLED));
        TRANSITIONS.put(ChangeStatus.PAUSED,
                EnumSet.of(ChangeStatus.IN_PROGRESS, ChangeStatus.CANCELLED));
        TRANSITIONS.put(ChangeStatus.FAILED,
                EnumSet.of(ChangeStatus.IN_PROGRESS, ChangeStatus.CANCELLED));
        TRANSITIONS.put(ChangeStatus.DONE, EnumSet.noneOf(ChangeStatus.class));
        TRANSITIONS.put(ChangeStatus.CANCELLED, EnumSet.noneOf(ChangeStatus.class));
    }

    private ChangeStateMachine() {
    }

    public static boolean canTransit(ChangeStatus from, ChangeStatus to) {
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(ChangeStatus.class)).contains(to);
    }

    public static void validate(ChangeStatus from, ChangeStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
