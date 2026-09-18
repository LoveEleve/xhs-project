import os
#!/usr/bin/env python3
"""
13-home 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
7 端点：feed/note/product/user/cart + 2 dev(push-inbox/push-outbox) + 2 异常
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
    u = f"home_{ts_}"
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
        "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", "myxhs-internal-2026"),
        "X-Admin-Call": os.environ.get("ADMIN_TOKEN", "myxhs-admin-2026"),
               "Content-Type": "application/json"}
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)
    r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                         json=json_body, params=params, timeout=15)  # BFF Feign 聚合较慢
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text[:300]}
    return r.status_code, body


def run():
    token, secret, user_id = login()
    print(f"\n=== Home 5 REST + 2 dev + 2 异常 ===\n")
    results = []

    # ===== 1. GET /api/home/feed（新用户 = 空 Feed） =====
    code, body = call("GET", "/api/home/feed", token, secret, user_id=user_id)
    results.append((1, "GET 首页Feed", (code, body)))

    # ===== 2. GET /api/home/note/{noteId}（不存在的笔记 → 404） =====
    code, body = call("GET", "/api/home/note/999999999999", token, secret, user_id=user_id)
    results.append((2, "GET 笔记详情(不存在)", (code, body)))

    # ===== 3. GET /api/home/product/{spuId}（公开端点，不存在的商品） =====
    code, body = call("GET", "/api/home/product/999999999999", token, secret)
    results.append((3, "GET 商品详情(不存在)", (code, body)))

    # ===== 4. GET /api/home/user/{targetUserId}（查看自己） =====
    code, body = call("GET", f"/api/home/user/{user_id}", token, secret, user_id=user_id)
    results.append((4, "GET 用户主页", (code, body)))

    # ===== 5. GET /api/home/cart（新用户 = 空购物车） =====
    code, body = call("GET", "/api/home/cart", token, secret, user_id=user_id)
    results.append((5, "GET 购物车", (code, body)))

    # ===== 6-7. dev 端点（@Profile("dev")，默认 profile 下不可用，预期 404） =====
    code, body = call("POST", "/api/home/test/push-inbox", token, secret,
                      params={"userId": user_id, "noteId": 999999999999})
    results.append((6, "POST push-inbox(dev)", (code, body)))

    code, body = call("POST", "/api/home/test/push-outbox", token, secret,
                      params={"authorId": user_id, "noteId": 999999999999})
    results.append((7, "POST push-outbox(dev)", (code, body)))

    # ===== 异常用例 =====
    # 8. feed 传异常 size（负数，service 层应收敛）
    code, body = call("GET", "/api/home/feed", token, secret,
                      params={"size": -1}, user_id=user_id)
    results.append((8, "异常: size=-1", (code, body)))

    # 9. cart 缺 X-User-Id（Gateway 自动注入 → 200；直连 19015 才会 40001）
    code, body = call("GET", "/api/home/cart", token, secret)
    results.append((9, "异常: cart缺userId", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_cnt = fail_cnt = 0
    for r in results:
        idx, name, (http_code, resp) = r
        rc = resp.get("code") if isinstance(resp, dict) else None
        msg = (resp.get("message") or resp.get("_raw") or str(resp) or "?")[:120] if isinstance(resp, dict) else (resp or "?")[:120]
        ok = False
        if idx in (1, 4, 5, 8, 9):  # feed/user/cart/exceptions: 200
            ok = (rc == 200)
        elif idx == 2:  # note detail nonexistent → 404
            ok = (rc == 404)
        elif idx == 3:  # product nonexistent → 404
            ok = (rc == 404)
        elif idx in (6, 7):  # dev 端点：dev profile 开启为 200，未开启为 404，均算通过
            ok = (rc in (200, 404))
        mark = "[PASS]" if ok else "[FAIL]"
        if ok: pass_cnt += 1
        else: fail_cnt += 1
        print(f"  {idx:>2}. {mark} {name:<28} http={http_code} code={rc} msg={msg}")

    print(f"\n=== 总计: {pass_cnt} PASS / {fail_cnt} FAIL / {pass_cnt+fail_cnt} TOTAL ===")
    return pass_cnt, fail_cnt, results


if __name__ == "__main__":
    run()
