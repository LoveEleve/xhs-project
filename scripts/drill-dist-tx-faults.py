#!/usr/bin/env python3
"""
分布式事务故障注入演练（可复用）
A 消费者宕机→积压→追平：停 counter → 发点赞事件 → LAG>0 → 重启 → LAG=0 且计数恰好一次
B MQ broker 短暂不可用：pause broker → 下单（事务消息发送失败，无半成品）→ unpause → 同 bizIdentifier 重试成功
产物：docs/reports/dist-tx-fault-drill-<ts>.json
安全说明：进程清理只匹配 comm==java 的进程，避免 pkill -f 误杀调用方 shell。
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("race", os.path.join(ROOT, "scripts/test-order-state-race.py"))
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)

RUN_TS = time.strftime("%Y%m%d-%H%M%S")
EV = []


def rec(name, detail, ok):
    EV.append({"scenario": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + f"{name}: {json.dumps(detail, ensure_ascii=False)[:200]}")


def java_pids(*patterns):
    """匹配 comm==java 且命令行含任一 pattern 的进程（避免误杀调用方 shell）"""
    out = subprocess.check_output(["ps", "-eo", "pid,comm,args"]).decode()
    pids = []
    for line in out.splitlines():
        parts = line.split(None, 2)
        if len(parts) == 3 and parts[1] == "java" and any(p in parts[2] for p in patterns):
            pids.append(int(parts[0]))
    return pids


def lag_of(group):
    cmd = ("cd /home/rocketmq/rocketmq-5.1.4/bin && ./mqadmin consumerProgress "
           f"-n 192.168.0.142:9876 -g {group} 2>/dev/null")
    out = subprocess.check_output(["docker", "exec", "my-xhs-mq-broker", "sh", "-c", cmd]).decode()
    total = 0
    for line in out.splitlines()[1:]:
        cols = line.split()
        if len(cols) >= 6 and cols[0] != "%RETRY%" + group and cols[0].startswith("SOCIAL_TOPIC"):
            try:
                total += int(cols[5])
            except ValueError:
                pass
    return total


def scenario_a():
    tok, sec, uid = R.login()
    _, body = R.call("POST", "/api/note/publish", tok, sec, uid,
                     json_body={"title": f"故障注入-{RUN_TS}", "content": "counter宕机追平", "images": [], "noteType": 0})
    nid = (body.get("data") or {}).get("noteId")
    base = R.call("GET", "/api/counter/get", tok, sec, uid,
                  params={"targetType": 1, "targetId": nid, "countType": 1})[1].get("data")

    pids = java_pids("my-xhs-counter-1.0-SNAPSHOT.jar", "releases/counter/")
    for p in pids:
        os.kill(p, 9)
    time.sleep(2)
    down = R.call("GET", "/api/counter/get", tok, sec, uid,
                  params={"targetType": 1, "targetId": nid, "countType": 1})[0] != 200

    R.call("POST", "/api/social/like", tok, sec, uid, json_body={"bizType": 1, "bizId": nid})
    time.sleep(3)
    lag_down = lag_of("counter-consumer-group")

    subprocess.run(["bash", "scripts/release-service.sh", "counter"], cwd=ROOT,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    drained = 0
    for _ in range(20):
        drained = lag_of("counter-consumer-group")
        if drained == 0:
            break
        time.sleep(6)
    cnt = R.call("GET", "/api/counter/get", tok, sec, uid,
                 params={"targetType": 1, "targetId": nid, "countType": 1})[1].get("data")
    R.call("DELETE", "/api/social/like", tok, sec, uid, json_body={"bizType": 1, "bizId": nid})
    time.sleep(2)
    cnt_after = R.call("GET", "/api/counter/get", tok, sec, uid,
                       params={"targetType": 1, "targetId": nid, "countType": 1})[1].get("data")
    rec("A 消费者宕机追平", {"noteId": nid, "base": base, "serviceDown": down,
                            "backlogWhileDown": lag_down, "drainedLag": drained,
                            "countAfterCatchup": cnt, "countAfterUnlike": cnt_after},
        down and lag_down >= 1 and drained == 0 and int(cnt) == 1 and int(cnt_after) == 0)


def scenario_b():
    tok, sec, uid = R.login()
    biz = f"mq-pause-{int(time.time())}"
    subprocess.run(["docker", "pause", "my-xhs-mq-broker"], check=True)
    t0 = time.time()
    code, oid, _, _ = R.create_order(tok, sec, uid, biz=biz)
    elapsed = time.time() - t0
    subprocess.run(["docker", "unpause", "my-xhs-mq-broker"], check=True)
    time.sleep(3)
    code2, oid2, _, _ = R.create_order(tok, sec, uid, biz=biz)
    _, ol = R.call("GET", "/api/order/list", tok, sec, uid, params={"pageNum": 1, "pageSize": 10})
    items = (ol.get("data") or {}).get("records") if isinstance(ol.get("data"), dict) else ol.get("data")
    rec("B MQ暂停下下单事务", {"duringPause": {"http": code, "orderId": oid, "elapsedSec": round(elapsed, 1)},
                              "afterRecover": {"http": code2, "orderId": oid2},
                              "userOrderCount": len(items or [])},
        oid is None and oid2 and len(items or []) == 1)


def main():
    scenario_a()
    scenario_b()
    out = os.path.join(ROOT, "docs", "reports", f"dist-tx-fault-drill-{RUN_TS}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"runTs": RUN_TS, "scenarios": EV}, f, ensure_ascii=False, indent=1)
    passed = sum(1 for e in EV if e["ok"])
    print(f"\n=== 汇总: {passed}/{len(EV)} PASS | 证据: {out} ===")
    return 0 if passed == len(EV) else 1


if __name__ == "__main__":
    sys.exit(main())
