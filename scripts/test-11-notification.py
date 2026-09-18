#!/usr/bin/env python3
"""
11-notification 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
9 端点：list/unread-count/read-all/sse-ticket/sse/read-id/read-by-type/test-send/online-count + 2 异常
"""
import base64, hashlib, hmac, json, time, uuid
import redis, requests
from redis.sentinel import Sentinel

GATEWAY = "http://localhost:19000"
_sentinel = Sentinel([("192.168.0.142", 26379), ("192.168.0.142", 26380), ("192.168.0.142", 26381)],
                     socket_timeout=3, password="Xhs@2026#Redis")
_h, _p = _sentinel.discover_master("mymaster")
REDIS = redis.Redis(host=_h, port=_p, db=0, password="Xhs@2026#Redis", decode_responses=True)


def login():
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    k = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{k}")
    code = (raw or "").strip('"')
    ts_ = int(time.time())
    u = f"notif_{ts_}"
    r = requests.post(f"{GATEWAY}/api/user/auth/register",
                      json={"username": u, "password": "Test@2026",
                            "captchaKey": k, "captchaCode": code}, timeout=5)
    print(f"[register] {r.json().get('code')} {r.json().get('message')}")
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    k = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{k}")
    code = (raw or "").strip('"')
    r = requests.post(f"{GATEWAY}/api/user/auth/login",
                      json={"username": u, "password": "Test@2026",
                            "captchaKey": k, "captchaCode": code}, timeout=5)
    d = r.json()
    token, secret = d["data"]["accessToken"], d["data"]["hmacSecret"]
    pb = token.split(".")[1] + "=" * (-len(token.split(".")[1]) % 4)
    claims = json.loads(base64.urlsafe_b64decode(pb))
    uid = int(claims.get("userId") or claims.get("sub") or claims.get("id"))
    print(f"[login] userId={uid}")
    return token, secret, uid


def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    return base64.b64encode(hmac.new(secret.encode(), msg, hashlib.sha256).digest()).decode()


def call(method, path, token, secret, json_body=None, params=None, user_id=None):
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sig = sign(secret, method, path.split("?")[0], ts, nonce)
    headers = {"Authorization": f"Bearer {token}",
               "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig,
        "X-Internal-Call": "myxhs-internal-2026",
        "X-Admin-Call": "myxhs-admin-2026",
               "Content-Type": "application/json"}
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)
    r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                         json=json_body, params=params, timeout=10)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text[:200]}
    return r.status_code, body


def run():
    token, secret, user_id = login()
    print(f"\n=== Notification 9 端点 + 2 异常 ===\n")
    results = []

    # ===== 1. GET /api/notification/list =====
    code, body = call("GET", "/api/notification/list", token, secret,
                      params={"page": 1, "size": 5}, user_id=user_id)
    results.append((1, "GET 通知列表", (code, body)))

    # ===== 2. GET /api/notification/unread-count =====
    code, body = call("GET", "/api/notification/unread-count", token, secret, user_id=user_id)
    results.append((2, "GET 未读数", (code, body)))

    # ===== 3. POST /api/notification/read-all =====
    code, body = call("POST", "/api/notification/read-all", token, secret, user_id=user_id)
    results.append((3, "POST 全部已读", (code, body)))

    # ===== 4. POST /api/notification/test/send（仅 dev profile，创建通知） =====
    code, body = call("POST", "/api/notification/test/send", token, secret,
                      json_body={"type": 4, "targetUserId": user_id,
                                 "senderName": "system", "content": "test-notif",
                                 "targetId": 0, "targetType": 0})
    results.append((4, "POST 发送通知(dev)", (code, body)))

    # ===== 5. GET /api/notification/list (验证通知已创建) =====
    code, body = call("GET", "/api/notification/list", token, secret,
                      params={"page": 1, "size": 5}, user_id=user_id)
    results.append((5, "GET 列表(验证)", (code, body)))
    notif_id = None
    if isinstance(body, dict) and isinstance(body.get("data"), dict):
        recs = body["data"].get("records", [])
        if recs:
            notif_id = recs[0].get("id")
    print(f"[debug] notif_id={notif_id}")

    # ===== 6. POST /api/notification/read/{id} =====
    if notif_id:
        code, body = call("POST", f"/api/notification/read/{notif_id}", token, secret,
                          user_id=user_id)
        results.append((6, "POST 标记已读", (code, body)))
    else:
        results.append((6, "POST 标记已读(跳过)", (0, {"_raw": "no notif_id"})))

    # ===== 7. POST /api/notification/read-by-type/{type} =====
    code, body = call("POST", f"/api/notification/read-by-type/4", token, secret, user_id=user_id)
    results.append((7, "POST 按类型已读", (code, body)))

    # ===== 8. POST /api/notification/sse/ticket =====
    code, body = call("POST", "/api/notification/sse/ticket", token, secret, user_id=user_id)
    results.append((8, "GET SSE Ticket", (code, body)))
    ticket = body.get("data", {}).get("ticket") if isinstance(body, dict) else None
    print(f"[debug] sse_ticket={ticket}")

    # ===== 9. GET /api/notification/sse?ticket=xxx（SSE 在白名单中，无需 HMAC） =====
    sse_ok = False
    if ticket:
        try:
            r = requests.get(f"{GATEWAY}/api/notification/sse",
                             params={"ticket": ticket},
                             stream=True, timeout=(3, 1))
            sse_ok = (r.status_code == 200)
            r.close()
            results.append((9, "GET SSE连接", (r.status_code, {"_raw": f"SSE established, status={r.status_code}"})))
        except requests.exceptions.ReadTimeout:
            sse_ok = True
            results.append((9, "GET SSE连接", (200, {"_raw": "connected (SSE waiting for events)"})))
        except Exception as e:
            results.append((9, "GET SSE连接", (0, {"_raw": str(e)})))
    else:
        results.append((9, "GET SSE连接", (0, {"_raw": "no ticket"})))

    # ===== 10. GET /api/notification/sse/online-count =====
    code, body = call("GET", "/api/notification/sse/online-count", token, secret)
    results.append((10, "GET 在线人数", (code, body)))

    # ===== 异常用例 =====
    # 11. SSE 连接不传 ticket（预期 400 或 40002）
    code, body = call("GET", "/api/notification/sse", token, secret)
    results.append((11, "异常: SSE缺ticket", (code, body)))

    # 12. 标记已读不存在的 ID
    code, body = call("POST", "/api/notification/read/99999999", token, secret, user_id=user_id)
    results.append((12, "异常: 读不存在通知", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_cnt = fail_cnt = 0
    for r in results:
        idx, name, (http_code, resp) = r
        rc = resp.get("code") if isinstance(resp, dict) else None
        msg = (resp.get("message") or resp.get("_raw") or str(resp) or "?")[:120] if isinstance(resp, dict) else (resp or "?")[:120]
        ok = False
        if idx in (3, 4, 6, 7):  # write endpoints
            ok = (rc == 200)
        elif idx in (1, 2, 5, 8, 10):  # GET endpoints
            ok = (rc == 200)
        elif idx == 9:  # SSE - check HTTP 200
            ok = (http_code == 200)
        elif idx == 11:  # SSE no ticket
            ok = (http_code in (400, 404, 500) or (isinstance(rc, int) and rc != 200))
        elif idx == 12:  # read nonexistent
            ok = (isinstance(rc, int) and rc != 200)
        mark = "[PASS]" if ok else "[FAIL]"
        if ok: pass_cnt += 1
        else: fail_cnt += 1
        print(f"  {idx:>2}. {mark} {name:<28} http={http_code} code={rc} msg={msg}")

    print(f"\n=== 总计: {pass_cnt} PASS / {fail_cnt} FAIL / {pass_cnt+fail_cnt} TOTAL ===")
    return pass_cnt, fail_cnt, results


if __name__ == "__main__":
    run()
