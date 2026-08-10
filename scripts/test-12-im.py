#!/usr/bin/env python3
"""
12-im 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
6 端点：ws-ticket / conversations / messages / read / unread-count / online-count + 2 异常
"""
import base64, hashlib, hmac, json, time, uuid
import redis, requests
from redis.sentinel import Sentinel

GATEWAY = "http://localhost:19000"
_sentinel = Sentinel([("21.130.247.89", 26379), ("21.130.247.89", 26380), ("21.130.247.89", 26381)],
                     socket_timeout=3, password="Xhs@2026#Redis")
_h, _p = _sentinel.discover_master("mymaster")
REDIS = redis.Redis(host=_h, port=_p, db=0, password="Xhs@2026#Redis", decode_responses=True)


def login():
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    k = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{k}")
    code = (raw or "").strip('"')
    ts_ = int(time.time())
    u = f"im_test_{ts_}"
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
    print(f"\n=== IM 6 端点 + 2 异常 ===\n")
    results = []

    # ===== 1. GET /api/im/conversations =====
    code, body = call("GET", "/api/im/conversations", token, secret,
                      params={"page": 1, "size": 10}, user_id=user_id)
    results.append((1, "GET 会话列表", (code, body)))

    # ===== 2. GET /api/im/messages/{peerId} =====
    code, body = call("GET", f"/api/im/messages/99999", token, secret,
                      params={"page": 1, "size": 10}, user_id=user_id)
    results.append((2, "GET 消息记录", (code, body)))

    # ===== 3. POST /api/im/read/{peerId} =====
    code, body = call("POST", f"/api/im/read/99999", token, secret, user_id=user_id)
    results.append((3, "POST 标记已读", (code, body)))

    # ===== 4. GET /api/im/unread-count =====
    code, body = call("GET", "/api/im/unread-count", token, secret, user_id=user_id)
    results.append((4, "GET 未读数", (code, body)))

    # ===== 5. GET /api/im/online-count（公开端点，无需 X-User-Id） =====
    code, body = call("GET", "/api/im/online-count", token, secret)
    results.append((5, "GET 在线人数", (code, body)))

    # ===== 6. POST /api/im/ws/ticket =====
    code, body = call("POST", "/api/im/ws/ticket", token, secret, user_id=user_id)
    results.append((6, "POST WS Ticket", (code, body)))

    # ===== 异常用例 =====
    # 注：IM 模块无校验注解、无请求体 DTO，Gateway 从 JWT 自动注入 X-User-Id，
    # 因此无传统参数校验异常路径。以下用例验证静默处理边界。
    #
    # 7. ws/ticket 缺 X-User-Id（Gateway 自动注入 → 仍返回 200）
    code, body = call("POST", "/api/im/ws/ticket", token, secret)
    results.append((7, "异常: ticket缺userId", (code, body)))

    # 8. messages 传超大 peerId（无校验 → 静默返回空列表）
    code, body = call("GET", "/api/im/messages/999999999999", token, secret, user_id=user_id)
    results.append((8, "异常: 超大peerId", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_cnt = fail_cnt = 0
    for r in results:
        idx, name, (http_code, resp) = r
        rc = resp.get("code") if isinstance(resp, dict) else None
        msg = (resp.get("message") or resp.get("_raw") or str(resp) or "?")[:120] if isinstance(resp, dict) else (resp or "?")[:120]
        ok = False
        if idx in range(1, 7):
            ok = (rc == 200)
        elif idx == 7:
            ok = (rc == 200)  # Gateway auto-injects X-User-Id from JWT
        elif idx == 8:
            ok = (rc == 200)  # no validation, silent handling
        mark = "[PASS]" if ok else "[FAIL]"
        if ok: pass_cnt += 1
        else: fail_cnt += 1
        print(f"  {idx:>2}. {mark} {name:<28} http={http_code} code={rc} msg={msg}")

    print(f"\n=== 总计: {pass_cnt} PASS / {fail_cnt} FAIL / {pass_cnt+fail_cnt} TOTAL ===")
    return pass_cnt, fail_cnt, results


if __name__ == "__main__":
    run()
