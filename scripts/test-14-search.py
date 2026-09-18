#!/usr/bin/env python3
"""
14-search 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
18 端点 + 2 异常
"""
import base64, hashlib, hmac, json, time, uuid, redis, requests
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
    u = f"srch_{ts_}"
    requests.post(f"{GATEWAY}/api/user/auth/register",
                  json={"username": u, "password": "Test@2026", "captchaKey": k, "captchaCode": code}, timeout=5)
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    k = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{k}")
    code = (raw or "").strip('"')
    r = requests.post(f"{GATEWAY}/api/user/auth/login",
                      json={"username": u, "password": "Test@2026", "captchaKey": k, "captchaCode": code}, timeout=5)
    d = r.json()
    token, secret = d["data"]["accessToken"], d["data"]["hmacSecret"]
    pb = token.split(".")[1] + "=" * (-len(token.split(".")[1]) % 4)
    uid = int(json.loads(base64.urlsafe_b64decode(pb)).get("userId") or
              json.loads(base64.urlsafe_b64decode(pb)).get("sub") or
              json.loads(base64.urlsafe_b64decode(pb)).get("id"))
    print(f"[login] userId={uid}")
    return token, secret, uid

def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    return base64.b64encode(hmac.new(secret.encode(), msg, hashlib.sha256).digest()).decode()

def call(method, path, token, secret, json_body=None, params=None, user_id=None):
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sig = sign(secret, method, path.split("?")[0], ts, nonce)
    headers = {"Authorization": f"Bearer {token}", "X-Timestamp": ts,
               "X-Nonce": nonce, "X-Signature": sig,
        "X-Internal-Call": "myxhs-internal-2026",
        "X-Admin-Call": "myxhs-admin-2026", "Content-Type": "application/json"}
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)
    r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                         json=json_body, params=params, timeout=30)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text[:300]}
    return r.status_code, body

def run():
    token, secret, user_id = login()
    print(f"\n=== Search 18 端点 + 2 异常 ===\n")
    R = []

    # ===== SearchController =====
    # 1. GET /api/search/note
    code, body = call("GET", "/api/search/note", token, secret,
                      params={"keyword": "test", "size": 5}, user_id=user_id)
    R.append((1, "GET 笔记搜索", (code, body)))

    # 2. GET /api/search/product
    code, body = call("GET", "/api/search/product", token, secret,
                      params={"keyword": "test", "size": 5}, user_id=user_id)
    R.append((2, "GET 商品搜索", (code, body)))

    # 3. GET /api/search/suggest (public)
    code, body = call("GET", "/api/search/suggest", token, secret,
                      params={"prefix": "te"})
    R.append((3, "GET 搜索建议", (code, body)))

    # 4. GET /api/search/history
    code, body = call("GET", "/api/search/history", token, secret, user_id=user_id)
    R.append((4, "GET 搜索历史", (code, body)))

    # 5. POST /api/search/hot/record
    code, body = call("POST", "/api/search/hot/record", token, secret,
                      params={"keyword": f"hello_{int(time.time())}"}, user_id=user_id)
    R.append((5, "POST 记录搜索词", (code, body)))

    # 6. GET /api/search/hot (public)
    code, body = call("GET", "/api/search/hot", token, secret)
    R.append((6, "GET 热搜榜", (code, body)))

    # 7. GET /api/search/hot/snapshot (public)
    code, body = call("GET", "/api/search/hot/snapshot", token, secret,
                      params={"date": "2026-08-03"})
    R.append((7, "GET 热搜快照", (code, body)))

    # 8. PUT /api/search/hot/pin (public admin)
    code, body = call("PUT", "/api/search/hot/pin", token, secret,
                      params={"keyword": f"pin_{int(time.time())}"})
    R.append((8, "PUT 置顶关键词", (code, body)))

    # 9. DELETE /api/search/hot/pin?keyword=xxx
    code, body = call("DELETE", "/api/search/hot/pin", token, secret,
                      params={"keyword": f"pin_{int(time.time())}"})
    R.append((9, "DELETE 取消置顶", (code, body)))

    # 10. PUT /api/search/hot/block (public admin)
    code, body = call("PUT", "/api/search/hot/block", token, secret,
                      params={"keyword": f"block_{int(time.time())}"})
    R.append((10, "PUT 屏蔽关键词", (code, body)))

    # 11. DELETE /api/search/hot/block?keyword=xxx
    code, body = call("DELETE", "/api/search/hot/block", token, secret,
                      params={"keyword": f"block_{int(time.time())}"})
    R.append((11, "DELETE 取消屏蔽", (code, body)))

    # 12. DELETE /api/search/history (clear)
    code, body = call("DELETE", "/api/search/history", token, secret, user_id=user_id)
    R.append((12, "DELETE 清空历史", (code, body)))

    # 13. DELETE /api/search/history/{keyword}
    code, body = call("DELETE", f"/api/search/history/test_word", token, secret, user_id=user_id)
    R.append((13, "DELETE 删除历史词", (code, body)))

    # 14. POST /api/search/index/rebuild (may be slow, timeout 60s)
    code, body = call("POST", "/api/search/index/rebuild", token, secret, user_id=user_id)
    R.append((14, "POST 索引重建", (code, body)))

    # ===== RecommendController =====
    # 15. GET /api/recommend/feed
    code, body = call("GET", "/api/recommend/feed", token, secret, user_id=user_id)
    R.append((15, "GET 推荐Feed", (code, body)))

    # 16. GET /api/recommend/similar/{noteId} (public)
    code, body = call("GET", "/api/recommend/similar/1", token, secret,
                      params={"size": 5})
    R.append((16, "GET 相似笔记", (code, body)))

    # 17. POST /api/recommend/behavior
    code, body = call("POST", "/api/recommend/behavior", token, secret,
                      json_body={"noteId": 1, "behaviorType": 1}, user_id=user_id)
    R.append((17, "POST 行为上报", (code, body)))

    # 18. POST /api/recommend/compute (public admin)
    code, body = call("POST", "/api/recommend/compute", token, secret)
    R.append((18, "POST 推荐计算", (code, body)))

    # ===== 异常用例 =====
    # 19. behavior 缺 noteId（唯一有校验注解的 DTO → 40002）
    code, body = call("POST", "/api/recommend/behavior", token, secret,
                      json_body={"behaviorType": 1}, user_id=user_id)
    R.append((19, "异常: 缺noteId", (code, body)))

    # 20. note search 空 keyword（无校验 → 静默返回空或默认）
    code, body = call("GET", "/api/search/note", token, secret, params={"size": 5})
    R.append((20, "异常: 空keyword", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    p = f = 0
    for idx, name, (http_code, resp) in R:
        rc = resp.get("code") if isinstance(resp, dict) else None
        msg = (resp.get("message") or resp.get("_raw") or str(resp) or "?")[:120] if isinstance(resp, dict) else (resp or "?")[:120]
        ok = (rc == 200)
        if idx == 19:
            ok = (rc == 40002)
        if idx == 20:
            ok = (rc == 200)  # empty keyword → silent default
        mark = "[PASS]" if ok else "[FAIL]"
        if ok: p += 1
        else: f += 1
        print(f"  {idx:>2}. {mark} {name:<28} http={http_code} code={rc} msg={msg}")

    print(f"\n=== 总计: {p} PASS / {f} FAIL / {p+f} TOTAL ===")
    return p, f, R

if __name__ == "__main__":
    run()
