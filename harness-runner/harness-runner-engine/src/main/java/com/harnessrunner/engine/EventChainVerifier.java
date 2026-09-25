package com.harnessrunner.engine;

import com.harnessrunner.domain.change.ChangeEvent;
import com.harnessrunner.engine.store.EventHasher;
import com.harnessrunner.engine.store.EventLog;

import java.util.List;
import java.util.Objects;

public final class EventChainVerifier {

    private final EventLog eventLog;

    public EventChainVerifier(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    public ChainVerification verify(String changeId) {
        List<ChangeEvent> events = eventLog.findByChangeId(changeId);
        int legacy = 0;
        int checked = 0;
        String previousHash = null;
        boolean chainStarted = false;

        for (int i = 0; i < events.size(); i++) {
            ChangeEvent event = events.get(i);
            if (!chainStarted && !event.hashed()) {
                legacy++;
                continue;
            }
            chainStarted = true;
            if (!event.hashed()) {
                return broken(checked, legacy, "第 " + (i + 1) + " 条事件缺少哈希（链断裂）");
            }
            String expected = EventHasher.hash(event, event.prevHash());
            if (!expected.equals(event.hash())) {
                return broken(checked, legacy, "第 " + (i + 1) + " 条事件哈希不匹配（疑似篡改）");
            }
            if (checked == 0 && !EventHasher.GENESIS.equals(event.prevHash())) {
                return broken(checked, legacy, "链首事件前向哈希不是 GENESIS");
            }
            if (checked > 0 && !Objects.equals(event.prevHash(), previousHash)) {
                return broken(checked, legacy, "第 " + (i + 1) + " 条事件前向哈希不连续");
            }
            previousHash = event.hash();
            checked++;
        }
        return new ChainVerification(true, checked, legacy,
                "链完整（已校验 " + checked + " 条，跳过历史未链化 " + legacy + " 条）");
    }

    private static ChainVerification broken(int checked, int legacy, String detail) {
        return new ChainVerification(false, checked, legacy, detail);
    }

    public record ChainVerification(boolean intact, int checkedEvents, int legacyEvents, String detail) {
    }
}
