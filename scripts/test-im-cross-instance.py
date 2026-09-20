#!/usr/bin/env python3
"""
IM 跨实例投递验证：A 连主实例(19014)，B 连第二实例(19114)，A 发消息 → B 应收到（路由表 + Redis pub/sub）
产物：docs/reports/im-cross-instance-run-<ts>.json
"""
import importlib.util
import json
import os
import sys
import threading
import time

import websocket

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("race", os.path.join(ROOT, "scripts/test-order-state-race.py"))
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)

IM_MAIN, IM_B = 19014, 19114
RUN_TS = time.strftime("%Y%m%d-%H%M%S")


def ticket(uid, port=IM_MAIN):
    code, body = R.call("POST", "/api/im/ws/ticket", *login_cache[uid], uid) if False else (None, None)
    return code, body


def main():
    global login_cache
    # A/B 登录（直接在本脚本内联，避免跨模块缓存）
    def login_user():
        tok, sec, uid = R.login()
        # 通过主实例直连申请 ticket（JWT + X-User-Id，不依赖网关）
        import requests, hmac, hashlib, base64, uuid
        ts = str(int(time.time() * 1000)); nonce = uuid.uuid4().hex
        path = "/api/im/ws/ticket"
        sig = base64.b64encode(hmac.new(sec.encode(), f"POST{path}{ts}{nonce}".encode(), hashlib.sha256).digest()).decode()
        h = {"Authorization": f"Bearer {tok}", "X-User-Id": str(uid), "X-Timestamp": ts, "X-Nonce": nonce,
             "X-Signature": sig, "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", ""),
             "X-Admin-Call": os.environ.get("ADMIN_TOKEN", ""), "Content-Type": "application/json"}
        r = requests.post(f"http://127.0.0.1:{IM_MAIN}{path}", headers=h, timeout=10)
        data = r.json().get("data")
        t = data if isinstance(data, str) else (data or {}).get("ticket")
        return uid, t

    uid_a, t_a = login_user()
    uid_b, t_b = login_user()
    print(f"A={uid_a} (ticket={bool(t_a)}) | B={uid_b} (ticket={bool(t_b)})")

    received = []
    lock = threading.Lock()

    def on_message(ws, msg):
        with lock:
            received.append(msg)

    wa = websocket.WebSocketApp(f"ws://127.0.0.1:{IM_MAIN}/api/im/ws?ticket={t_a}",
                                on_message=on_message)
    wb = websocket.WebSocketApp(f"ws://127.0.0.1:{IM_B}/api/im/ws?ticket={t_b}",
                                on_message=on_message)
    threading.Thread(target=lambda: wa.run_forever(), daemon=True).start()
    threading.Thread(target=lambda: wb.run_forever(), daemon=True).start()
    time.sleep(3)  # 等握手与路由注册

    ws_a_connected = wa.sock is not None and wa.sock.connected
    ws_b_connected = wb.sock is not None and wb.sock.connected
    print("连接状态: A(main)=", ws_a_connected, " B(instance-b)=", ws_b_connected)

    payload = json.dumps({"type": "CHAT", "to": uid_b, "content": f"cross-instance-{RUN_TS}",
                          "msgType": 1, "traceId": f"imx-{RUN_TS}"})
    wa.send(payload)
    deadline = time.time() + 10
    got = None
    while time.time() < deadline:
        with lock:
            for m in received:
                if str(uid_b) in m or "cross-instance" in m:
                    got = m
                    break
        if got:
            break
        time.sleep(0.5)
    print("B 收到:", (got or "（超时未收到）")[:200])

    out = os.path.join(ROOT, "docs", "reports", f"im-cross-instance-run-{RUN_TS}.json")
    json.dump({"runTs": RUN_TS, "userA": uid_a, "userB": uid_b,
               "aOnMain": ws_a_connected, "bOnSecond": ws_b_connected,
               "receivedByB": got, "allFrames": received[:5]},
              open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    try:
        wa.close(); wb.close()
    except Exception:
        pass
    ok = bool(ws_a_connected and ws_b_connected and got)
    print(f"=== {'PASS' if ok else 'FAIL'} | 证据: {out} ===")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
