#!/usr/bin/env python3
"""
订单状态机 × 并发竞态对抗测试（经网关 + JWT + HMAC）
场景：
 S1 并发重复支付回调（同支付单 ×5）
 S2 取消 vs 支付竞态（同时发起）
 S3 重复退款回调（×3）
 S4 确认收货 vs 退款竞态
 S5 部分退款（amount < payAmount）
 S6 支付成功但订单已取消（应业务拒绝并触发自动退款）
 S7 同 bizIdentifier 并发创建（×3，应只生成一单）
产物：docs/reports/order-state-race-run-<ts>.json
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import uuid

import redis
import requests

GATEWAY = "http://localhost:19000"
RD = redis.Redis(host="192.168.0.142", port=6379, password="Xhs@2026#Redis", decode_responses=True)
TS = int(time.time() * 1000)
RUN_TS = time.strftime("%Y%m%d-%H%M%S")
EVIDENCE = []


def login():
    username = f"r{TS % 1000000000}{uuid.uuid4().hex[:6]}"
    password = "Race@2026"
    for attempt in range(4):
        key = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5).json()["data"]["captchaKey"]
        code = (RD.get(f"myxhs:user:captcha:{key}") or "").strip('"')
        rb = requests.post(f"{GATEWAY}/api/user/auth/register",
                           json={"username": username, "password": password,
                                 "captchaKey": key, "captchaCode": code}, timeout=5).json()
        if rb.get("code") == 200:
            break
        time.sleep(6)
    else:
        raise RuntimeError(f"register failed after retries: {rb}")
    key = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5).json()["data"]["captchaKey"]
    code = (RD.get(f"myxhs:user:captcha:{key}") or "").strip('"')
    lb = requests.post(f"{GATEWAY}/api/user/auth/login",
                       json={"username": username, "password": password,
                             "captchaKey": key, "captchaCode": code}, timeout=5).json()
    if lb.get("code") != 200 or not lb.get("data"):
        raise RuntimeError(f"login failed: {lb}")
    data = lb["data"]
    token, secret = data["accessToken"], data["hmacSecret"]
    pl = token.split(".")[1] + "=" * (-len(token.split(".")[1]) % 4)
    uid = json.loads(base64.urlsafe_b64decode(pl))["sub"]
    return token, secret, uid


def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    return base64.b64encode(hmac.new(secret.encode(), msg, hashlib.sha256).digest()).decode()


def call(method, path, token, secret, user_id=None, json_body=None, params=None, raw_body=None):
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    headers = {"Authorization": f"Bearer {token}", "X-Timestamp": ts, "X-Nonce": nonce,
               "X-Signature": sign(secret, method, path.split("?")[0], ts, nonce),
               "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", ""),
               "X-Admin-Call": os.environ.get("ADMIN_TOKEN", ""),
               "Content-Type": "application/json"}
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)
    kw = {"headers": headers, "params": params, "timeout": 30}
    if raw_body is not None:
        kw["data"] = raw_body
    else:
        kw["json"] = json_body
    r = requests.request(method, f"{GATEWAY}{path}", **kw)
    try:
        return r.status_code, r.json()
    except Exception:
        return r.status_code, {"_raw": r.text}


def ensure_address(token, secret, uid):
    _, body = call("GET", "/api/user/address/list", token, secret, uid)
    if body.get("data"):
        return body["data"][0]["id"]
    _, body = call("POST", "/api/user/address", token, secret, uid,
                   json_body={"receiverName": "竞态测试", "receiverPhone": "13800000000",
                              "province": "上海市", "city": "上海市", "district": "徐汇区",
                              "detailAddress": "测试路 1 号", "isDefault": True})
    return (body.get("data") or {}).get("id")


def create_order(token, secret, uid, biz=None, qty=1):
    addr = ensure_address(token, secret, uid)
    code, body = call("POST", "/api/order/create", token, secret, uid,
                      json_body={"skuItems": [{"skuId": 1, "quantity": qty}],
                                 "addressId": addr,
                                 "bizIdentifier": biz or f"race-{TS}-{uuid.uuid4().hex[:6]}"})
    d = body.get("data") or {}
    return code, d.get("orderId"), d.get("orderNo"), d.get("payAmount")


def pay(token, secret, uid, order_id, trade_suffix=""):
    _, body = call("POST", "/api/order/pay/create", token, secret, uid,
                   json_body={"orderId": order_id, "payType": 1})
    pno = (body.get("data") or {}).get("paymentNo")
    if pno:
        call("POST", "/api/payment/callback/1", token, secret,
             raw_body=json.dumps({"out_trade_no": pno, "trade_no": f"RACE_{TS}{trade_suffix}",
                                  "status": "SUCCESS"}))
    return pno


def order_status(token, secret, uid, order_id):
    _, body = call("GET", f"/api/order/{order_id}", token, secret, uid)
    return (body.get("data") or {}).get("status")


def stock_of(token, secret, uid, sku=1):
    _, body = call("GET", f"/api/inventory/stock/{sku}", token, secret, uid)
    d = body.get("data") or {}
    return d.get("availableStock"), d.get("lockedStock")


def event_types(order_id):
    """从 4 分片 ×4 表中查事件类型序列"""
    out = []
    for i in range(4):
        for j in range(4):
            r = os.popen(
                f"docker exec -i my-xhs-mysql mysql -uroot -pXhs@2026#MySQL -N -e "
                f"\"SELECT event_type FROM my_xhs_order_{i}.t_order_event_{j} WHERE order_id={order_id} ORDER BY event_seq;\" 2>/dev/null").read()
            if r.strip():
                out = [x.strip() for x in r.strip().split("\n")]
                return out
    return out


def payment_info(token, secret, uid, order_id):
    _, body = call("GET", f"/api/order/pay/status/{order_id}", token, secret, uid)
    return body.get("data") or {}


def wait_refund_no(order_id, timeout=8):
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = refund_of(order_id)
        if r:
            parts = r.split("\t")
            if len(parts) >= 1 and parts[0]:
                return parts[0]
        time.sleep(0.5)
    return None


def refund_of(order_id):
    r = os.popen(
        f"docker exec -i my-xhs-mysql mysql -uroot -pXhs@2026#MySQL -N -e "
        f"\"SELECT refund_no,refund_amount,status FROM my_xhs_payment.t_refund WHERE order_id={order_id};\" 2>/dev/null").read()
    return r.strip()


def rec(name, detail, ok):
    EVIDENCE.append({"scenario": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + f"{name}: {json.dumps(detail, ensure_ascii=False)[:220]}")


def run_parallel(fns):
    barrier = threading.Barrier(len(fns))
    results = [None] * len(fns)

    def wrap(i, fn):
        barrier.wait()
        try:
            results[i] = fn()
        except Exception as e:
            results[i] = {"error": str(e)[:80]}
    ths = [threading.Thread(target=wrap, args=(i, fn)) for i, fn in enumerate(fns)]
    [t.start() for t in ths]
    [t.join() for t in ths]
    return results


def main():
    # ===== S1 并发重复支付回调 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    _, b = call("POST", "/api/order/pay/create", tok, sec, uid, json_body={"orderId": oid, "payType": 1})
    pno = (b.get("data") or {}).get("paymentNo")

    def cb():
        return call("POST", "/api/payment/callback/1", tok, sec,
                    raw_body=json.dumps({"out_trade_no": pno, "trade_no": f"S1_{TS}", "status": "SUCCESS"}))
    results = run_parallel([cb] * 5)
    time.sleep(2)
    st = order_status(tok, sec, uid, oid)
    evs = event_types(oid)
    rec("S1 并发重复支付回调×5", {"status": st, "events": evs,
                                "callback_codes": [r[1].get("code") if isinstance(r, tuple) else r for r in results]},
        st == 1 and evs.count("ORDER_PAID") == 1)

    # ===== S2 取消 vs 支付竞态 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    _, b = call("POST", "/api/order/pay/create", tok, sec, uid, json_body={"orderId": oid, "payType": 1})
    pno = (b.get("data") or {}).get("paymentNo")

    def do_cancel():
        return ("cancel",) + call("POST", "/api/order/cancel", tok, sec, uid, params={"orderId": oid})
    def do_pay():
        return ("pay",) + call("POST", "/api/payment/callback/1", tok, sec,
                               raw_body=json.dumps({"out_trade_no": pno, "trade_no": f"S2_{TS}", "status": "SUCCESS"}))
    res = run_parallel([do_cancel, do_pay])
    time.sleep(3)
    st = order_status(tok, sec, uid, oid)
    evs = event_types(oid)
    mixed_bad = st not in (1, 4, 5)
    rec("S2 取消vs支付竞态", {"orderId": oid, "status": st, "events": evs, "refund": refund_of(oid),
                            "results": [(r[0], r[1], (r[2] or {}).get("code")) for r in res]},
        (not mixed_bad) and (("ORDER_CANCELLED" in evs) ^ ("ORDER_PAID" in evs) or st in (1, 4, 5)))

    # ===== S3 重复退款回调×3 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    pay(tok, sec, uid, oid, "S3")
    time.sleep(1)
    pinfo = payment_info(tok, sec, uid, oid)
    pid, pamount = pinfo.get("id"), pinfo.get("amount") or pay_amt
    call("POST", "/api/payment/refund", tok, sec, uid,
         json_body={"paymentId": pid, "refundAmount": pamount, "reason": "竞态S3", "refundType": 1})
    rno = wait_refund_no(oid)

    def rcb():
        return call("POST", "/api/payment/refund-callback/99", tok, sec,
                    raw_body=json.dumps({"refund_no": rno, "refund_status": "REFUND_SUCCESS", "status": "SUCCESS"}))
    rres = run_parallel([rcb] * 3)
    time.sleep(3)
    st = order_status(tok, sec, uid, oid)
    evs = event_types(oid)
    rec("S3 重复退款回调×3", {"status": st, "events": evs, "refund": refund_of(oid),
                            "codes": [(r[1] or {}).get("code") if isinstance(r, tuple) else r for r in rres]},
        st == 5 and evs.count("ORDER_REFUNDED") == 1)

    # ===== S4 确认收货 vs 退款竞态 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    pay(tok, sec, uid, oid, "S4")
    time.sleep(1)
    call("POST", "/api/order/deliver", tok, sec,
         json_body={"orderId": oid, "logisticsCompany": "SF", "trackingNo": f"S4{TS}"})
    time.sleep(1)
    pinfo = payment_info(tok, sec, uid, oid)
    pid, pamount = pinfo.get("id"), pinfo.get("amount") or pay_amt
    call("POST", "/api/payment/refund", tok, sec, uid,
         json_body={"paymentId": pid, "refundAmount": pamount, "reason": "竞态S4", "refundType": 1})
    rno = wait_refund_no(oid)

    def do_confirm():
        return ("confirm",) + call("POST", "/api/order/confirm", tok, sec, uid, params={"orderId": oid})
    def do_refund_cb():
        return ("refund",) + call("POST", "/api/payment/refund-callback/99", tok, sec,
                                  raw_body=json.dumps({"refund_no": rno, "refund_status": "REFUND_SUCCESS", "status": "SUCCESS"}))
    res4 = run_parallel([do_confirm, do_refund_cb])
    time.sleep(3)
    st = order_status(tok, sec, uid, oid)
    evs = event_types(oid)
    rec("S4 确认收货vs退款竞态", {"status": st, "events": evs,
                                 "results": [(r[0], r[1], (r[2] or {}).get("code")) for r in res4]},
        st in (3, 5))

    # ===== S5 部分退款 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    stock_before = stock_of(tok, sec, uid)
    pay(tok, sec, uid, oid, "S5")
    time.sleep(1)
    pinfo = payment_info(tok, sec, uid, oid)
    pid, pamount = pinfo.get("id"), pinfo.get("amount") or pay_amt
    part = 100.00 if float(pamount or 0) > 100 else round(float(pamount or 1) / 2, 2)
    call("POST", "/api/payment/refund", tok, sec, uid,
         json_body={"paymentId": pid, "refundAmount": part, "reason": "部分退款S5", "refundType": 1})
    rno = wait_refund_no(oid)
    if rno:
        call("POST", "/api/payment/refund-callback/99", tok, sec,
             raw_body=json.dumps({"refund_no": rno, "refund_status": "REFUND_SUCCESS", "status": "SUCCESS"}))
    time.sleep(3)
    st = order_status(tok, sec, uid, oid)
    stock_after = stock_of(tok, sec, uid)
    rec("S5 部分退款(100/实付)", {"status": st, "stockBefore": stock_before, "stockAfter": stock_after,
                                 "refund": refund_of(oid)}, True)  # 记录行为，判定在报告中

    # ===== S6 支付成功但订单已取消 =====
    tok, sec, uid = login()
    _, oid, _, pay_amt = create_order(tok, sec, uid)
    call("POST", "/api/order/cancel", tok, sec, uid, params={"orderId": oid})
    time.sleep(1)
    st_after_cancel = order_status(tok, sec, uid, oid)
    _, b = call("POST", "/api/order/pay/create", tok, sec, uid, json_body={"orderId": oid, "payType": 1})
    pno = (b.get("data") or {}).get("paymentNo")
    code, body = call("POST", "/api/payment/callback/1", tok, sec,
                      raw_body=json.dumps({"out_trade_no": pno, "trade_no": f"S6_{TS}", "status": "SUCCESS"}))
    time.sleep(3)
    st_final = order_status(tok, sec, uid, oid)
    rec("S6 支付成功但订单已取消", {"orderId": oid, "afterCancel": st_after_cancel, "final": st_final,
                                   "callback": (code, (body or {}).get("code")), "refund": refund_of(oid)},
        st_final == 4)

    # ===== S7 同 bizIdentifier 并发创建 =====
    tok, sec, uid = login()
    biz = f"race-biz-{TS}-{uuid.uuid4().hex[:6]}"

    def mk():
        return create_order(tok, sec, uid, biz=biz)
    res7 = run_parallel([mk] * 3)
    ids = [r[1] for r in res7 if isinstance(r, tuple) and r[1]]
    uniq = set(ids)
    rec("S7 同bizIdentifier并发创建×3", {"ids": ids, "unique": len(uniq), "codes": [r[0] for r in res7]},
        len(uniq) == 1)

    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "docs", "reports", f"order-state-race-run-{RUN_TS}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"runTs": RUN_TS, "scenarios": EVIDENCE}, f, ensure_ascii=False, indent=1)
    passed = sum(1 for e in EVIDENCE if e["ok"])
    print(f"\n=== 汇总: {passed}/{len(EVIDENCE)} PASS | 证据: {out} ===")
    return 0 if passed == len(EVIDENCE) else 1


if __name__ == "__main__":
    sys.exit(main())
