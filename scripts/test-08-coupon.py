import os
#!/usr/bin/env python3
"""
08-coupon 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
10 个用例：8 正常 + 2 异常
"""
import base64
import hashlib
import hmac
import json
import time
import uuid

import redis
import requests
from redis.sentinel import Sentinel

GATEWAY = "http://localhost:19000"
_sentinel = Sentinel([("192.168.0.142", 26379), ("192.168.0.142", 26380), ("192.168.0.142", 26381)],
                     socket_timeout=3, password="Xhs@2026#Redis")
_master_host, _master_port = _sentinel.discover_master("mymaster")
REDIS = redis.Redis(host=_master_host, port=_master_port, db=0,
                    password="Xhs@2026#Redis", decode_responses=True)


def login():
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    key = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{key}")
    code = (raw or "").strip('"')
    ts = int(time.time())
    username = f"cpn_tester_{ts}"
    password = "Test@2026"
    r = requests.post(f"{GATEWAY}/api/user/auth/register",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code}, timeout=5)
    print(f"[register] {r.json().get('code')} {r.json().get('message')}")
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    key = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{key}")
    code = (raw or "").strip('"')
    r = requests.post(f"{GATEWAY}/api/user/auth/login",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code}, timeout=5)
    data = r.json()
    token = data["data"]["accessToken"]
    secret = data["data"]["hmacSecret"]
    payload_b64 = token.split(".")[1] + "=" * (-len(token.split(".")[1]) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload_b64))
    user_id = int(claims.get("userId") or claims.get("sub") or claims.get("id"))
    print(f"[login] userId={user_id}")
    return token, secret, user_id


def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    return base64.b64encode(hmac.new(secret.encode(), msg, hashlib.sha256).digest()).decode()


def call(method, path, token, secret, json_body=None, params=None):
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sig = sign(secret, method, path.split("?")[0], ts, nonce)
    headers = {"Authorization": f"Bearer {token}",
               "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig,
        "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", "myxhs-internal-2026"),
        "X-Admin-Call": os.environ.get("ADMIN_TOKEN", "myxhs-admin-2026"),
               "Content-Type": "application/json"}
    r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                         json=json_body, params=params, timeout=10)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text}
    return r.status_code, body


def run():
    token, secret, user_id = login()
    print(f"\n=== Coupon 8 个接口 + 2 异常 ===\n")
    results = []

    # 1. 创建券模板
    now = int(time.time() * 1000)
    # Jackson 全局配置：LocalDateTime 格式 "yyyy-MM-dd HH:mm:ss"（空格分隔，非 ISO T 分隔）
    # validStart 有 @FutureOrPresent 校验：取"当前+3s"并在领券前等待生效
    from datetime import datetime, timedelta
    valid_start = (datetime.now() + timedelta(seconds=3)).strftime("%Y-%m-%d %H:%M:%S")
    valid_end = (datetime.now() + timedelta(days=30)).strftime("%Y-%m-%d %H:%M:%S")
    code, body = call("POST", "/api/coupon/template", token, secret,
                      json_body={"name": f"auto-test-{now}",
                                 "type": 1, "discountValue": 20.00,
                                 "minAmount": 100.00, "totalCount": 100,
                                 "perUserLimit": 2,
                                 "validStart": valid_start, "validEnd": valid_end})
    results.append((1, "POST 创建券模板", (code, body)))
    new_template_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
    time.sleep(4)  # 等 validStart 生效（@FutureOrPresent + 有效期校验收口）

    # 2. 修改券模板状态（下线=0 → 上线=1）
    code, body = call("PUT", f"/api/coupon/template/{new_template_id}/status?status=0",
                      token, secret)
    results.append((2, "PUT 下线券模板", (code, body)))
    code, body = call("PUT", f"/api/coupon/template/{new_template_id}/status?status=1",
                      token, secret)
    # 用第二次状态修改作为用例 2（最终为上线）
    results.append((2, "PUT 上线券模板", (code, body)))

    # 3. 查券模板详情
    code, body = call("GET", f"/api/coupon/template/{new_template_id}", token, secret)
    results.append((3, "GET 查券模板", (code, body)))

    # 4. 领券（用新建的模板）
    code, body = call("POST", "/api/coupon/claim", token, secret,
                      json_body={"templateId": new_template_id})
    results.append((4, "POST 领券", (code, body)))

    # 5. 我的券列表
    code, body = call("GET", "/api/coupon/user/list", token, secret)
    results.append((5, "GET 我的券列表", (code, body)))
    user_coupon_id = None
    if isinstance(body, dict) and isinstance(body.get("data"), list):
        for c in body["data"]:
            if c.get("couponId") == new_template_id:
                user_coupon_id = c.get("id")
                break
    print(f"[debug] new_template_id={new_template_id} user_coupon_id={user_coupon_id}")

    # 6. 可用券
    code, body = call("GET", "/api/coupon/user/available", token, secret)
    results.append((6, "GET 可用券", (code, body)))

    # 7. 用券
    order_id = now
    code, body = call("POST", "/api/coupon/use", token, secret,
                      json_body={"userCouponId": user_coupon_id,
                                 "orderId": order_id, "orderAmount": 150.00})
    results.append((7, "POST 用券", (code, body)))

    # 8. 退券
    code, body = call("POST", "/api/coupon/return", token, secret,
                      json_body={"userCouponId": user_coupon_id, "orderId": order_id})
    results.append((8, "POST 退券", (code, body)))

    # ===== 异常 =====
    # 9. 领不存在模板的券（预期 30012 COUPON_NOT_FOUND）
    code, body = call("POST", "/api/coupon/claim", token, secret,
                      json_body={"templateId": 99999999999})
    results.append((9, "异常: 领不存在的券模板", (code, body)))

    # 10. 用券缺 userCouponId（预期 40002 PARAM_INVALID）
    code, body = call("POST", "/api/coupon/use", token, secret,
                      json_body={"orderId": order_id, "orderAmount": 100.00})
    results.append((10, "异常: 用券缺 userCouponId", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_count = 0
    fail_count = 0
    for r in results:
        idx, name, (code, body) = r
        resp_code = body.get("code") if isinstance(body, dict) else None
        msg = body.get("message") if isinstance(body, dict) else str(body)[:80]
        ok = False
        if idx in (1, 2, 3, 4, 5, 6, 7, 8):
            ok = (resp_code == 200)
        elif idx == 9:
            ok = (resp_code == 30012)
        elif idx == 10:
            ok = (resp_code == 40002)
        mark = "[PASS]" if ok else "[FAIL]"
        if ok: pass_count += 1
        else: fail_count += 1
        print(f"  {idx:>2}. {mark} {name:<24} http={code} code={resp_code} msg={msg}")

    print(f"\n=== 总计: {pass_count} PASS / {fail_count} FAIL / {pass_count+fail_count} TOTAL ===")
    return pass_count, fail_count, results


if __name__ == "__main__":
    run()
