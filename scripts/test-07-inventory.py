import os
#!/usr/bin/env python3
"""
07-inventory 接口测试脚本（通过 Gateway 19000 + JWT + HMAC per-session secret）
11 个用例：9 正常 + 2 异常
"""
import base64
import hashlib
import hmac
import json
import time
import uuid

import redis
import requests

GATEWAY = "http://localhost:19000"
# Redis 通过 Sentinel 找 master
from redis.sentinel import Sentinel
_sentinel = Sentinel([("192.168.0.142", 26379), ("192.168.0.142", 26380), ("192.168.0.142", 26381)],
                     socket_timeout=3, password="Xhs@2026#Redis")
_master_host, _master_port = _sentinel.discover_master("mymaster")
REDIS = redis.Redis(host=_master_host, port=_master_port, db=0,
                    password="Xhs@2026#Redis", decode_responses=True)

# ---------- 1. 登录获取 JWT + per-session hmacSecret ----------
def login():
    # 1.1 获取图形验证码
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    print(f"[captcha] {r.status_code}")
    cap = r.json()["data"]
    key = cap["captchaKey"]
    # 1.2 从 Redis 读 code（key=myxhs:user:captcha:{captchaKey}，Jackson 带引号）
    raw = REDIS.get(f"myxhs:user:captcha:{key}")
    code = (raw or "").strip('"')
    print(f"[captcha code] key={key[:8]}... code={code}")

    # 1.3 注册测试用户（如已存在则忽略报错）
    ts = int(time.time())
    username = f"inv_tester_{ts}"
    password = "Test@2026"
    r = requests.post(f"{GATEWAY}/api/user/auth/register",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code},
                      timeout=5)
    print(f"[register] {r.status_code} {r.text[:160]}")

    # 1.4 登录（需重新获取图形验证码）
    r = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5)
    key = r.json()["data"]["captchaKey"]
    raw = REDIS.get(f"myxhs:user:captcha:{key}")
    code = (raw or "").strip('"')
    r = requests.post(f"{GATEWAY}/api/user/auth/login",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code},
                      timeout=5)
    data = r.json()
    print(f"[login] code={data.get('code')} msg={data.get('message')}")
    token = data["data"]["accessToken"]
    secret = data["data"]["hmacSecret"]
    # JWT payload 中含 userId/sub claim
    payload_b64 = token.split(".")[1]
    # 补齐 base64 padding
    payload_b64 += "=" * (-len(payload_b64) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload_b64))
    user_id = claims.get("userId") or claims.get("sub") or claims.get("id")
    print(f"[login] userId={user_id} token={(token or '')[:30]}... secret={secret[:8]}...")
    return token, secret, int(user_id)


# ---------- 2. HMAC 签名 ----------
def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    sig = hmac.new(secret.encode(), msg, hashlib.sha256).digest()
    return base64.b64encode(sig).decode()


def call(method, path, token, secret, json_body=None, params=None):
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sig = sign(secret, method, path, ts, nonce)
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Timestamp": ts,
        "X-Nonce": nonce,
        "X-Signature": sig,
        "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", "myxhs-internal-2026"),
        "X-Admin-Call": os.environ.get("ADMIN_TOKEN", "myxhs-admin-2026"),
        "Content-Type": "application/json",
    }
    url = f"{GATEWAY}{path}"
    r = requests.request(method, url, headers=headers, json=json_body,
                         params=params, timeout=10)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text}
    return r.status_code, body


# ---------- 3. 测试用例 ----------
def run():
    token, secret, user_id = login()
    print(f"\n=== Inventory 11 个接口 ===\n")

    results = []

    # 准备 SKU
    sku_id = int(time.time()) % 100000 + 100  # 测试用 SKU
    order_id = int(time.time() * 1000)
    results.append(("prep", f"使用 skuId={sku_id} orderId={order_id} userId={user_id}", None))

    # 1. 初始化库存
    code, body = call("POST", "/api/inventory/init", token, secret,
                      json_body={"skuId": sku_id, "totalStock": 1000, "bucketCount": 3})
    results.append((1, "POST 初始化库存", (code, body)))

    # 2. 查库存
    code, body = call("GET", f"/api/inventory/stock/{sku_id}", token, secret)
    results.append((2, "GET 查库存", (code, body)))

    # 3. 预扣减（带 userId）
    code, body = call("POST", "/api/inventory/preDeduct", token, secret,
                      json_body={"orderId": order_id, "skuId": sku_id,
                                 "quantity": 2, "userId": user_id})
    results.append((3, "POST 预扣减", (code, body)))

    # 4. 确认扣减
    code, body = call("POST", "/api/inventory/confirm", token, secret,
                      json_body={"orderId": order_id})
    results.append((4, "POST 确认扣减", (code, body)))

    # 5. 释放库存（用新 orderId 触发一次预扣+释放）
    order_id2 = order_id + 1
    call("POST", "/api/inventory/preDeduct", token, secret,
         json_body={"orderId": order_id2, "skuId": sku_id,
                    "quantity": 1, "userId": user_id})
    code, body = call("POST", "/api/inventory/release", token, secret,
                      json_body={"orderId": order_id2})
    results.append((5, "POST 释放库存", (code, body)))

    # 6. 重新初始化
    code, body = call("POST", "/api/inventory/reinit", token, secret,
                      json_body={"skuId": sku_id, "bucketCount": 2})
    results.append((6, "POST 重新初始化", (code, body)))

    # 7. TCC Try
    xid = f"test:{order_id}"
    branch_id = 1
    code, body = call("POST", "/api/inventory/tcc/try", token, secret,
                      json_body={"xid": xid, "branchId": branch_id,
                                 "skuItems": [{"skuId": sku_id, "quantity": 3}]})
    results.append((7, "POST TCC Try", (code, body)))

    # 8. TCC Confirm
    code, body = call("POST", "/api/inventory/tcc/confirm", token, secret,
                      json_body={"xid": xid, "branchId": branch_id,
                                 "skuItems": [{"skuId": sku_id, "quantity": 3}]})
    results.append((8, "POST TCC Confirm", (code, body)))

    # 9. TCC Cancel（用新 xid 触发一次 Try + Cancel）
    xid2 = f"test:{order_id + 1000}"
    call("POST", "/api/inventory/tcc/try", token, secret,
         json_body={"xid": xid2, "branchId": 2,
                    "skuItems": [{"skuId": sku_id, "quantity": 1}]})
    code, body = call("POST", "/api/inventory/tcc/cancel", token, secret,
                      json_body={"xid": xid2, "branchId": 2,
                                 "skuItems": [{"skuId": sku_id, "quantity": 1}]})
    results.append((9, "POST TCC Cancel", (code, body)))

    # ===== 异常用例 =====
    # 10. 预扣减缺 skuId
    code, body = call("POST", "/api/inventory/preDeduct", token, secret,
                      json_body={"orderId": order_id, "quantity": 1, "userId": user_id})
    results.append((10, "异常: 预扣减缺 skuId", (code, body)))

    # 11. 查不存在库存（预期 30002）
    code, body = call("GET", "/api/inventory/stock/99999999999", token, secret)
    results.append((11, "异常: 查不存在库存", (code, body)))

    # ---------- 输出结果 ----------
    print("\n=== 结果汇总 ===")
    pass_count = 0
    fail_count = 0
    for r in results:
        if r[0] == "prep":
            print(f"  PREP: {r[1]}")
            continue
        idx, name, (code, body) = r
        resp_code = body.get("code") if isinstance(body, dict) else None
        msg = body.get("message") if isinstance(body, dict) else str(body)[:80]
        # 判定通过条件
        ok = False
        if idx in (1, 2, 3, 4, 5, 6, 7, 8, 9):
            ok = (resp_code == 200)
        elif idx == 10:
            ok = (resp_code == 40002)  # 参数校验
        elif idx == 11:
            ok = (resp_code == 30002)  # SKU_NOT_FOUND
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
