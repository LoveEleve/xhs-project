#!/usr/bin/env python3
"""
跨域业务全链路 E2E（单用户主线，经 Gateway 19000）
链路：注册登录 → 地址 → 商品浏览(缓存) → 搜索 → 加购 → 领券 → 下单(预扣库存) →
      支付(渠道回调) → 库存确认 → 发货/确认收货 → 通知 → 发布笔记/评论 → 退款回补(库存/券)
产物：docs/reports/e2e-business-chain-run-<ts>.json（逐步证据）
"""
import base64
import hashlib
import hmac
import json
import os
import sys
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
    key = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5).json()["data"]["captchaKey"]
    code = (RD.get(f"myxhs:user:captcha:{key}") or "").strip('"')
    username = f"chain_{TS}"
    password = "Chain@2026"
    r = requests.post(f"{GATEWAY}/api/user/auth/register",
                      json={"username": username, "password": password,
                            "captchaKey": key, "captchaCode": code}, timeout=5)
    assert r.json().get("code") == 200, f"register failed: {r.text}"
    key = requests.get(f"{GATEWAY}/api/user/auth/captcha", timeout=5).json()["data"]["captchaKey"]
    code = (RD.get(f"myxhs:user:captcha:{key}") or "").strip('"')
    data = requests.post(f"{GATEWAY}/api/user/auth/login",
                         json={"username": username, "password": password,
                               "captchaKey": key, "captchaCode": code}, timeout=5).json()["data"]
    token, secret = data["accessToken"], data["hmacSecret"]
    pl = token.split(".")[1] + "=" * (-len(token.split(".")[1]) % 4)
    uid = json.loads(base64.urlsafe_b64decode(pl))["sub"]
    return token, secret, uid


def sign(secret, method, path, ts, nonce):
    msg = f"{method}{path}{ts}{nonce}".encode()
    return base64.b64encode(hmac.new(secret.encode(), msg, hashlib.sha256).digest()).decode()


def call(method, path, token, secret, json_body=None, params=None, user_id=None, raw_body=None):
    time.sleep(0.5)
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    headers = {"Authorization": f"Bearer {token}",
               "X-Timestamp": ts, "X-Nonce": nonce,
               "X-Signature": sign(secret, method, path.split("?")[0], ts, nonce),
               "X-Internal-Call": os.environ.get("INTERNAL_TOKEN", ""),
               "X-Admin-Call": os.environ.get("ADMIN_TOKEN", ""),
               "Content-Type": "application/json"}
    if user_id is not None:
        headers["X-User-Id"] = str(user_id)
    if raw_body is not None:
        r = requests.request(method, f"{GATEWAY}{path}", headers=headers, data=raw_body,
                             params=params, timeout=60)
    else:
        r = requests.request(method, f"{GATEWAY}{path}", headers=headers, json=json_body,
                             params=params, timeout=60)
    try:
        body = r.json()
    except Exception:
        body = {"_raw": r.text}
    return r.status_code, body


def step(no, name, code, body, ok, extra=None):
    rec = {"no": no, "name": name, "http": code, "ok": bool(ok),
           "data": (body or {}).get("data") if isinstance(body, dict) else None}
    if extra:
        rec.update(extra)
    EVIDENCE.append(rec)
    mark = "PASS" if ok else "FAIL"
    snippet = json.dumps(rec.get("data"), ensure_ascii=False)[:150] if rec.get("data") is not None else ""
    print(f"[{mark}] {no:2d}. {name} | http={code} | {snippet}")
    return rec


def main():
    token, secret, uid = login()
    print(f"[login] userId={uid}")

    # 0. 地址
    code, body = call("GET", "/api/user/address/list", token, secret, user_id=uid)
    addrs = body.get("data") or []
    if addrs:
        addr_id = addrs[0]["id"]
    else:
        code, body = call("POST", "/api/user/address", token, secret, user_id=uid,
                          json_body={"receiverName": "链路测试", "receiverPhone": "13800000000",
                                     "province": "上海市", "city": "上海市", "district": "徐汇区",
                                     "detailAddress": "测试路 1 号", "isDefault": True})
        addr_id = (body.get("data") or {}).get("id")
    step(0, "创建/获取收货地址", 200, body, bool(addr_id))

    # 1. 商品浏览（多级缓存路径）
    code, body = call("GET", "/api/product/spu/list", token, secret,
                      params={"pageNum": 1, "pageSize": 5}, user_id=uid)
    spu_list = (body.get("data") or {}).get("list") or body.get("data") or []
    spu_id = (spu_list[0].get("id") if isinstance(spu_list, list) and spu_list else 1)
    code2, body2 = call("GET", f"/api/product/spu/{spu_id}", token, secret, user_id=uid)
    sku_id = 1
    try:
        skus = (body2.get("data") or {}).get("skus") or (body2.get("data") or {}).get("skuList") or []
        if skus:
            sku_id = skus[0].get("id") or skus[0].get("skuId") or 1
    except Exception:
        pass
    step(1, f"商品列表+详情(spuId={spu_id},skuId={sku_id})", code2, body2,
         code2 == 200 and body2.get("code") == 200)

    # 2. 搜索（Canal→ES 索引链路）
    code, body = call("GET", "/api/search/product", token, secret,
                      params={"keyword": "test", "size": 5}, user_id=uid)
    step(2, "商品搜索(ES)", code, body, code == 200)
    code, body = call("GET", "/api/search/note", token, secret,
                      params={"keyword": "test", "size": 5}, user_id=uid)
    step(3, "笔记搜索(ES)", code, body, code == 200)

    # 3. 加购（Redis 三结构）
    code, body = call("POST", "/api/cart/add", token, secret, user_id=uid,
                      json_body={"skuId": sku_id, "quantity": 2})
    step(4, "加购 sku×2", code, body, code == 200)
    code, body = call("GET", "/api/cart/list", token, secret, user_id=uid)
    step(5, "购物车列表", code, body, code == 200)

    # 4. 领券（模板→领取）
    now = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 3))
    end = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 30 * 86400))
    code, body = call("POST", "/api/coupon/template", token, secret,
                      json_body={"name": f"chain-{TS}", "type": 1, "discountValue": 5.00,
                                 "minAmount": 0.01, "totalCount": 100, "perUserLimit": 2,
                                 "validStart": now, "validEnd": end})
    tpl_id = (body.get("data") or {}).get("id")
    step(6, "创建券模板", code, body, bool(tpl_id))
    time.sleep(4)
    code, body = call("POST", "/api/coupon/claim", token, secret,
                      json_body={"templateId": tpl_id}, user_id=uid)
    step(7, "领取优惠券", code, body, code == 200)
    code, body = call("GET", "/api/coupon/user/list", token, secret, user_id=uid)
    user_coupon_id = None
    for c in (body.get("data") or []):
        if c.get("couponId") == tpl_id or c.get("templateId") == tpl_id:
            user_coupon_id = c.get("id")
            break
    step(8, f"我的券(userCouponId={user_coupon_id})", code, body, bool(user_coupon_id))

    # 5. 库存快照（预扣前）
    code, body = call("GET", f"/api/inventory/stock/{sku_id}", token, secret, user_id=uid)
    stock_before = (body.get("data") or {}).get("stock") if isinstance(body.get("data"), dict) else body.get("data")
    step(9, f"库存快照(下单前): {stock_before}", code, body, code == 200)

    # 6. 下单（用券 + 预扣库存）
    code, body = call("POST", "/api/order/create", token, secret, user_id=uid,
                      json_body={"skuItems": [{"skuId": sku_id, "quantity": 2}],
                                 "addressId": addr_id, "couponId": user_coupon_id,
                                 "bizIdentifier": f"chain-{TS}-order"})
    order_id = (body.get("data") or {}).get("orderId")
    order_no = (body.get("data") or {}).get("orderNo")
    pay_amount = (body.get("data") or {}).get("payAmount")
    step(10, f"创建订单 orderId={order_id}", code, body, bool(order_id),
         {"orderId": order_id, "orderNo": order_no, "payAmount": pay_amount})
    code, body = call("GET", f"/api/order/{order_id}", token, secret, user_id=uid)
    status_created = (body.get("data") or {}).get("status")
    step(11, f"订单详情(下单后): status={status_created}", code, body, code == 200)

    # 7. 库存快照（预扣后）
    code, body = call("GET", f"/api/inventory/stock/{sku_id}", token, secret, user_id=uid)
    stock_after = (body.get("data") or {}).get("stock") if isinstance(body.get("data"), dict) else body.get("data")
    step(12, f"库存快照(预扣后): {stock_after}", code, body, code == 200,
         {"stockBefore": stock_before, "stockAfter": stock_after})

    # 8. 支付（创建支付单→渠道回调）
    code, body = call("POST", "/api/order/pay/create", token, secret, user_id=uid,
                      json_body={"orderId": order_id, "payType": 1})
    payment_no = (body.get("data") or {}).get("paymentNo")
    payment_id = (body.get("data") or {}).get("paymentId")
    step(13, f"创建支付单 paymentNo={payment_no}", code, body, bool(payment_no))
    code, body = call("POST", "/api/payment/callback/1", token, secret,
                      raw_body=json.dumps({"out_trade_no": payment_no,
                                           "trade_no": f"CHAIN_{TS}",
                                           "status": "SUCCESS"}))
    step(14, "支付渠道回调(SUCCESS)", code, body, code == 200)
    code, body = call("GET", f"/api/order/{order_id}", token, secret, user_id=uid)
    status_paid = (body.get("data") or {}).get("status")
    step(15, f"订单详情(支付后): status={status_paid}", code, body, code == 200)
    code, body = call("GET", f"/api/payment/status/{order_id}", token, secret, user_id=uid)
    pay_status = (body.get("data") or {}).get("status") if isinstance(body.get("data"), dict) else None
    payment_id = payment_id or ((body.get("data") or {}).get("id") if isinstance(body.get("data"), dict) else None)
    step(16, f"支付单状态: {pay_status} paymentId={payment_id}", code, body, code == 200)

    # 9. 履约：发货→确认收货
    code, body = call("POST", "/api/order/deliver", token, secret,
                      json_body={"orderId": order_id, "logisticsCompany": "SF",
                                 "trackingNo": f"SF{TS}"})
    step(17, "订单发货", code, body, code == 200)
    code, body = call("POST", "/api/order/confirm", token, secret,
                      params={"orderId": order_id}, user_id=uid)
    step(18, "确认收货", code, body, code == 200)
    code, body = call("GET", f"/api/order/{order_id}", token, secret, user_id=uid)
    status_done = (body.get("data") or {}).get("status")
    step(19, f"订单终态: status={status_done}", code, body, code == 200)

    # 10. 通知（订单事件应有落库）
    code, body = call("GET", "/api/notification/list", token, secret,
                      params={"pageNum": 1, "pageSize": 10}, user_id=uid)
    _nd = body.get("data")
    notif_cnt = len(_nd) if isinstance(_nd, list) else len((_nd or {}).get("records") or (_nd or {}).get("list") or [])
    step(20, f"通知列表(条数={notif_cnt})", code, body, code == 200 and notif_cnt >= 1)

    # 11. 内容：发笔记→评论
    code, body = call("POST", "/api/note/publish", token, secret, user_id=uid,
                      json_body={"title": f"链路笔记{TS}", "content": "E2E 全链路验证笔记",
                                 "images": [], "noteType": 0})
    note_id = (body.get("data") or {}).get("noteId") or (body.get("data") or {}).get("id")
    step(21, f"发布笔记 noteId={note_id}", code, body, bool(note_id))
    code, body = call("POST", "/api/comment", token, secret, user_id=uid,
                      json_body={"noteId": note_id, "content": "链路评论"})
    comment_id = (body.get("data") or {}).get("commentId")
    step(22, f"发表评论 commentId={comment_id}", code, body, bool(comment_id))
    code, body = call("GET", f"/api/comment/list/{note_id}", token, secret, user_id=uid)
    step(23, "评论列表", code, body, code == 200)

    # 12. 退款：发起→渠道回调→回补
    code, body = call("POST", "/api/payment/refund", token, secret, user_id=uid,
                      json_body={"paymentId": payment_id, "refundAmount": pay_amount or 1.00,
                                 "reason": "链路验证退款", "refundType": 1})
    step(24, "发起退款", code, body, code == 200)
    code, body = call("POST", "/api/payment/refund-callback/99", token, secret,
                      raw_body=json.dumps({"refund_no": f"REFUND_{TS}",
                                           "refund_status": "REFUND_SUCCESS",
                                           "status": "SUCCESS"}))
    step(25, "退款渠道回调", code, body, code == 200)
    time.sleep(2)
    code, body = call("GET", f"/api/order/{order_id}", token, secret, user_id=uid)
    status_refund = (body.get("data") or {}).get("status")
    step(26, f"订单详情(退款后): status={status_refund}", code, body, code == 200)

    # 13. 回补验证：库存 + 券
    time.sleep(1)
    code, body = call("GET", f"/api/inventory/stock/{sku_id}", token, secret, user_id=uid)
    stock_final = (body.get("data") or {}).get("stock") if isinstance(body.get("data"), dict) else body.get("data")
    step(27, f"库存快照(退款后): {stock_final}", code, body, code == 200,
         {"stockBefore": stock_before, "stockAfter": stock_after, "stockFinal": stock_final})
    code, body = call("GET", "/api/coupon/user/list", token, secret, user_id=uid)
    coupon_status = None
    for c in (body.get("data") or []):
        if c.get("id") == user_coupon_id:
            coupon_status = c.get("status")
            break
    step(28, f"券状态(退款后): {coupon_status}", code, body, code == 200,
         {"userCouponId": user_coupon_id, "couponStatus": coupon_status})

    # 14. 订单通知（退款后应刷新为最新状态：订单域接入验证）
    code, body = call("GET", "/api/notification/list", token, secret,
                      params={"pageNum": 1, "pageSize": 10}, user_id=uid)
    _nd2 = body.get("data")
    _recs = _nd2 if isinstance(_nd2, list) else (_nd2 or {}).get("records") or (_nd2 or {}).get("list") or []
    order_notifs = [r for r in _recs if r.get("type") == 5]
    latest_content = order_notifs[0].get("content") if order_notifs else None
    step(29, f"订单通知(退款后): {len(order_notifs)}条 | {latest_content}", code, body,
         bool(order_notifs) and "退款已到账" in (latest_content or ""),
         {"titles": [r.get("title") for r in order_notifs],
          "contents": [r.get("content") for r in order_notifs]})

    out_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "docs", "reports")
    out = os.path.join(out_dir, f"e2e-business-chain-run-{RUN_TS}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"runTs": RUN_TS, "userId": uid, "orderId": order_id,
                   "steps": EVIDENCE}, f, ensure_ascii=False, indent=1)
    passed = sum(1 for e in EVIDENCE if e["ok"])
    print(f"\n=== 汇总: {passed}/{len(EVIDENCE)} PASS | 证据: {out} ===")
    return 0 if passed == len(EVIDENCE) else 1


if __name__ == "__main__":
    sys.exit(main())
