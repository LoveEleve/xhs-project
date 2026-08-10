#!/usr/bin/env python3
"""
09-order 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
14 个用例：12 正常 + 2 异常
"""
import base64
import hashlib
import hmac
import json
import time
import uuid

import requests

GATEWAY = "http://localhost:19000"


def login():
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    key = r.json()["data"]["captchaKey"]
    import redis
    from redis.sentinel import Sentinel
    _sentinel = Sentinel([("21.130.247.89", 26379), ("21.130.247.89", 26380), ("21.130.247.89", 26381)],
                         socket_timeout=3, password="Xhs@2026#Redis")
    _host, _port = _sentinel.discover_master("mymaster")
    rds = redis.Redis(host=_host, port=_port, db=0,
                      password="Xhs@2026#Redis", decode_responses=True)
    raw = rds.get(f"myxhs:user:captcha:{key}")
    code = (raw or "").strip('"')
    ts = int(time.time())
    username = f"order_tester_{ts}"
    password = "Test@2026"
    r = requests.post(f"{GATEWAY}/api/user/auth/register",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code}, timeout=5)
    print(f"[register] {r.json().get('code')} {r.json().get('message')}")
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    key = r.json()["data"]["captchaKey"]
    raw = rds.get(f"myxhs:user:captcha:{key}")
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
                         json=json_body, params=params, timeout=60)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text}
    return r.status_code, body


def run():
    token, secret, user_id = login()
    print(f"\n=== Order 14 接口 + 2 异常 ===\n")
    results = []
    ts_base = int(time.time() * 1000)

    # ===== 1. 创建订单 A（无优惠券，走完整状态机） =====
    biz_id = f"order-test-{ts_base}-main"
    order_create_body = {
        "skuItems": [{"skuId": 999, "quantity": 2}],
        "addressId": 1,
        "bizIdentifier": biz_id
    }
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body=order_create_body, user_id=user_id)
    results.append((1, "POST 创建订单", (code, body)))
    order_id_a = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    order_no_a = body.get("data", {}).get("orderNo") if isinstance(body, dict) else None
    print(f"[debug] orderId={order_id_a} orderNo={order_no_a}")

    # ===== 2. 订单详情 =====
    code, body = call("GET", f"/api/order/{order_id_a}", token, secret, user_id=user_id)
    results.append((2, "GET 订单详情", (code, body)))

    # ===== 3. 我的订单列表 =====
    code, body = call("GET", "/api/order/list", token, secret, user_id=user_id)
    results.append((3, "GET 订单列表", (code, body)))

    # ===== 4. 按订单号查询（无需 X-User-Id，走映射表） =====
    code, body = call("GET", f"/api/order/by-order-no/{order_no_a}", token, secret)
    results.append((4, "GET 按订单号查询", (code, body)))

    # ===== 5. 支付（mock 模式，payType=1 支付宝避免 30% 随机失败） =====
    code, body = call("POST", "/api/order/pay/create", token, secret,
                      json_body={"orderId": order_id_a, "payType": 1}, user_id=user_id)
    results.append((5, "POST 创建支付", (code, body)))

    # ===== 6. 支付状态查询 =====
    code, body = call("GET", f"/api/order/pay/status/{order_id_a}", token, secret)
    results.append((6, "GET 支付状态", (code, body)))

    # ===== 7. 发货（状态 1→2） =====
    code, body = call("POST", "/api/order/deliver", token, secret,
                      json_body={"orderId": order_id_a,
                                 "logisticsCompany": "SF", "trackingNo": "SF00000001"},
                      user_id=user_id)
    results.append((7, "POST 发货", (code, body)))

    # ===== 8. 确认收货（状态 2→3） =====
    code, body = call("POST", "/api/order/confirm", token, secret,
                      params={"orderId": order_id_a}, user_id=user_id)
    results.append((8, "POST 确认收货", (code, body)))

    # ===== 9. 取消订单（新建订单 B，再取消） =====
    biz_b = f"order-test-{ts_base}-cancel"
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"skuItems": [{"skuId": 999, "quantity": 1}],
                                 "addressId": 1, "bizIdentifier": biz_b},
                      user_id=user_id)
    order_id_b = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    print(f"[debug] cancel target orderId={order_id_b}")
    code, body = call("POST", "/api/order/cancel", token, secret,
                      params={"orderId": order_id_b}, user_id=user_id)
    results.append((9, "POST 取消订单", (code, body)))

    # ===== 10. 支付成功回调（新建订单 C，直接回调） =====
    biz_c = f"order-test-{ts_base}-paysuccess"
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"skuItems": [{"skuId": 999, "quantity": 1}],
                                 "addressId": 1, "bizIdentifier": biz_c},
                      user_id=user_id)
    order_id_c = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    print(f"[debug] paysuccess target orderId={order_id_c}")
    code, body = call("POST", "/api/order/pay-success", token, secret,
                      params={"orderId": order_id_c, "tradeNo": "trade-test-001"})
    results.append((10, "POST 支付成功回调", (code, body)))

    # ===== 11. 支付失败回调（新建订单 D） =====
    biz_d = f"order-test-{ts_base}-payfail"
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"skuItems": [{"skuId": 999, "quantity": 1}],
                                 "addressId": 1, "bizIdentifier": biz_d},
                      user_id=user_id)
    order_id_d = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    print(f"[debug] payfail target orderId={order_id_d}")
    code, body = call("POST", "/api/order/pay-fail", token, secret,
                      params={"orderId": order_id_d})
    results.append((11, "POST 支付失败回调", (code, body)))

    # ===== 12. 退款成功回调（新建订单 E，先支付再回调） =====
    biz_e = f"order-test-{ts_base}-refundok"
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"skuItems": [{"skuId": 999, "quantity": 1}],
                                 "addressId": 1, "bizIdentifier": biz_e},
                      user_id=user_id)
    order_id_e = body.get("data", {}).get("orderId") if isinstance(body, dict) else None
    call("POST", "/api/order/pay/create", token, secret,
         json_body={"orderId": order_id_e, "payType": 1}, user_id=user_id)
    print(f"[debug] refund-success target orderId={order_id_e}")
    code, body = call("POST", "/api/order/refund-success", token, secret,
                      params={"orderId": order_id_e, "refundNo": "refund-001"})
    results.append((12, "POST 退款成功回调", (code, body)))

    # ===== 13. 退款失败回调（复用订单 E，仅记日志不写状态） =====
    print(f"[debug] refund-fail target orderId={order_id_e}")
    code, body = call("POST", "/api/order/refund-fail", token, secret,
                      params={"orderId": order_id_e, "refundNo": "refund-002"})
    results.append((13, "POST 退款失败回调", (code, body)))

    # ===== 14. 查询支付金额 =====
    code, body = call("GET", "/api/order/pay-amount", token, secret,
                      params={"orderId": order_id_a})
    results.append((14, "GET 支付金额", (code, body)))

    # ===== 异常用例 =====
    # 15. 创建订单缺 skuItems（预期 40002 PARAM_INVALID）
    code, body = call("POST", "/api/order/create", token, secret,
                      json_body={"addressId": 1, "bizIdentifier": f"order-test-{ts_base}-err1"},
                      user_id=user_id)
    results.append((15, "异常: 缺skuItems", (code, body)))

    # 16. 取消不存在的订单（预期 30xxx 业务错误）
    code, body = call("POST", "/api/order/cancel", token, secret,
                      params={"orderId": 999999999999}, user_id=user_id)
    results.append((16, "异常: 取消不存在订单", (code, body)))

    # ---------- 输出 ----------
    print("\n=== 结果汇总 ===")
    pass_count = 0
    fail_count = 0
    for r in results:
        idx, name, (code, body) = r
        resp_code = body.get("code") if isinstance(body, dict) else None
        msg = body.get("message") if isinstance(body, dict) else str(body)[:120]
        ok = False
        if idx in range(1, 15):
            ok = (resp_code == 200)
        elif idx == 15:
            ok = (resp_code == 40002)
        elif idx == 16:
            ok = (resp_code is not None and resp_code != 200)  # 任意业务错误都算通过
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
