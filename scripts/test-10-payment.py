import os
#!/usr/bin/env python3
"""
10-payment 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
5 个端点：pay / callback / refund / refund-callback / status + 2 异常
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
    username = f"pay_tester_{ts}"
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


def call(method, path, token, secret, json_body=None, params=None, user_id=None, raw_body=None):
    time.sleep(0.6)  # 限速：payment 5 QPS，避免测试触发 429 干扰断言
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
    if raw_body is not None:
        r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                             data=raw_body, params=params, timeout=10)
    else:
        r = requests.request(method, f"{GATEWAY}{path}", headers=headers,
                             json=json_body, params=params, timeout=10)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text}
    return r.status_code, body


def run():
    token, secret, user_id = login()

    # 前置：确保用户有收货地址（新注册用户无地址会导致下单失败）
    code, body = call("GET", "/api/user/address/list", token, secret, user_id=user_id)
    _addr_list = (body or {}).get("data") or []
    if _addr_list:
        ADDR = _addr_list[0].get("id")
    else:
        code, body = call("POST", "/api/user/address", token, secret, user_id=user_id,
                          json_body={"receiverName": "测试用户", "receiverPhone": "13800000000",
                                     "province": "上海市", "city": "上海市", "district": "徐汇区",
                                     "detailAddress": "测试路 1 号", "isDefault": True})
        ADDR = (body or {}).get("data", {}).get("id")
    print(f"\n=== Payment 5 接口 + 2 异常 ===\n")
    results = []
    ts_base = int(time.time() * 1000)

    # ----- 前置：创建订单（需要 valid orderId 来支付） -----
    biz_id = f"pay-test-{ts_base}"
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"skuItems": [{"skuId": 1, "quantity": 1}],
                                 "addressId": ADDR, "bizIdentifier": biz_id},
                      user_id=user_id)
    order_id = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    order_no = body.get("data", {}).get("orderNo") if isinstance(body, dict) else None
    print(f"[prep] orderId={order_id} orderNo={order_no}")

    # ===== 1. POST /api/payment/pay（Mock 模式 payType=99） =====
    code, body = call("POST", "/api/payment/pay", token, secret,
                      json_body={"orderId": order_id, "amount": 99.00, "payType": 99},
                      user_id=user_id)
    results.append((1, "POST 发起支付", (code, body)))
    payment_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
    payment_no = body.get("data", {}).get("paymentNo") if isinstance(body, dict) else None
    print(f"[debug] paymentId={payment_id} paymentNo={payment_no}")

    # ===== 2. GET /api/payment/status/{orderId} =====
    code, body = call("GET", f"/api/payment/status/{order_id}", token, secret)
    results.append((2, "GET 支付状态", (code, body)))

    # ===== 3. POST /api/payment/callback/{payType} =====
    # 回调返回 String（非 R<JSON>），需特殊解析
    callback_data = json.dumps({"out_trade_no": payment_no or f"PAY_{ts_base}",
                                 "trade_no": f"MOCKPAY_{ts_base}",
                                 "status": "SUCCESS"})
    code, body = call("POST", "/api/payment/callback/99", token, secret,
                      raw_body=callback_data)
    callback_ok = (code == 200 and isinstance(body, dict) and
                   body.get("_raw", "").strip() == "success")
    results.append((3, "POST 支付回调", (code, body), callback_ok))

    # ===== 4. POST /api/payment/refund =====
    code, body = call("POST", "/api/payment/refund", token, secret,
                      json_body={"paymentId": payment_id, "refundAmount": 99.00,
                                 "reason": "测试退款", "refundType": 1},
                      user_id=user_id)
    results.append((4, "POST 发起退款", (code, body)))

    # ===== 5. POST /api/payment/refund-callback/{payType} =====
    callback_data = json.dumps({"refund_no": f"REFUND_{ts_base}",
                                 "refund_status": "REFUND_SUCCESS",
                                 "status": "SUCCESS"})
    code, body = call("POST", "/api/payment/refund-callback/99", token, secret,
                      raw_body=callback_data)
    rc_ok = (code == 200 and isinstance(body, dict) and
             body.get("_raw", "").strip() == "success")
    results.append((5, "POST 退款回调", (code, body), rc_ok))

    # ===== 异常用例 =====
    # 6. 支付缺 orderId（预期 40002 PARAM_INVALID）
    code, body = call("POST", "/api/payment/pay", token, secret,
                      json_body={"amount": 99.00, "payType": 99}, user_id=user_id)
    results.append((6, "异常: 缺orderId", (code, body)))

    # 7. 退款缺 paymentId（预期 40002 PARAM_INVALID）
    code, body = call("POST", "/api/payment/refund", token, secret,
                      json_body={"refundAmount": 99.00}, user_id=user_id)
    results.append((7, "异常: 缺paymentId", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_count = 0
    fail_count = 0
    for r in results:
        if len(r) == 4:
            idx, name, (code, body), override_ok = r
        else:
            idx, name, (code, body) = r
            override_ok = None
        resp_code = body.get("code") if isinstance(body, dict) else None
        msg = body.get("message") if isinstance(body, dict) else body.get("_raw", str(body))[:120]
        ok = override_ok
        if ok is None:
            if idx in range(1, 6):
                ok = (resp_code == 200)
            elif idx == 6:
                ok = (resp_code == 40002)
            elif idx == 7:
                ok = (resp_code == 40002)
        mark = "[PASS]" if ok else "[FAIL]"
        if ok:
            pass_count += 1
        else:
            fail_count += 1
        print(f"  {idx:>2}. {mark} {name:<24} http={code} code={resp_code} msg={msg}")

    print(f"\n=== 总计: {pass_count} PASS / {fail_count} FAIL / {pass_count+fail_count} TOTAL ===")
    return pass_count, fail_count, results


if __name__ == "__main__":
    run()
