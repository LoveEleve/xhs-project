"""G1 测试公共工具库（test-3/helpers）
用法：from testlib import *（与 testlib.py 同目录执行）
"""
import hmac, hashlib, time, uuid, base64, json, random, subprocess
import urllib.request, urllib.error
import redis as redislib

BASE = "http://localhost:19000"
REDIS_HOST, REDIS_PORT, REDIS_PASS = "21.130.247.89", 6379, "Xhs@2026#Redis"
r = redislib.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASS, decode_responses=True)

def mysql(sql):
    return subprocess.run(["mysql", "-h21.130.247.89", "-uroot", "-pXhs@2026#MySQL", "-N", "-e", sql],
                          capture_output=True, text=True).stdout.strip()

def b64(d): return base64.urlsafe_b64encode(d).rstrip(b'=').decode()
def jwt_claims(token):
    return json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '=='))

def sign(secret, method, path, query="", ts=None, nonce=None, body=None):
    """新签名算法（T-009/010/011）：method|path|query|ts|nonce|bodyHash，Base64 输出"""
    ts = ts or str(int(time.time() * 1000))
    nonce = nonce or uuid.uuid4().hex
    # 网关语义：cachedBody 为 null 时 bodyHash=""（非 sha256(空)）
    bh = hashlib.sha256(body).hexdigest() if body else ""
    s = f"{method}|{path}|{query}|{ts}|{nonce}|{bh}"
    return base64.b64encode(hmac.new(secret.encode(), s.encode(), hashlib.sha256).digest()).decode(), ts, nonce

def call(method, path, token=None, body=None, secret=None, sig=None, raw=False, query="", headers=None):
    req = urllib.request.Request(BASE + path + (("?" + query) if query else ""), method=method)
    if token: req.add_header("Authorization", "Bearer " + token)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    if secret:
        # R6（2026-08-13）：query 参与签名（T-009/010/011 新算法：method|path|query|ts|nonce|bodyHash）
        s, ts, nonce = sign(secret, method, path, query=query, body=data)
        req.add_header("X-Timestamp", ts); req.add_header("X-Nonce", nonce); req.add_header("X-Signature", s)
    elif sig:
        ts, nonce, s = sig
        req.add_header("X-Timestamp", ts); req.add_header("X-Nonce", nonce); req.add_header("X-Signature", s)
    try:
        resp = urllib.request.urlopen(req, data=data, timeout=8)
        return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read())
        except Exception: return e.code, {"err": "non-json"}

def get_captcha():
    st, c = call("GET", "/api/user/auth/captcha")
    assert c.get("code") == 200, f"验证码获取失败: {c}"
    key = c["data"]["captchaKey"]
    code = r.get(f"myxhs:user:captcha:{key}")
    return key, code

def new_user(prefix="g1"):
    """注册+登录全新用户，返回 dict(token/secret/refresh/uid/username)"""
    ts_u = str(int(time.time()))[-6:]
    username = f"{prefix}_{ts_u}"
    phone = "13" + str(random.randint(5, 9)) + "".join(random.choices("0123456789", k=8))
    k, c = get_captcha()
    st, reg = call("POST", "/api/user/auth/register", body={"username": username, "password": "Test@123456",
                                                             "phone": phone, "captchaKey": k, "captchaCode": c})
    assert reg.get("code") == 200, f"注册失败: {reg}"
    k2, c2 = get_captcha()
    st, login = call("POST", "/api/user/auth/login", body={"username": username, "password": "Test@123456",
                                                            "captchaKey": k2, "captchaCode": c2})
    assert login.get("code") == 200, f"登录失败: {login}"
    d = login["data"]
    return {"username": username, "phone": phone, "token": d["accessToken"],
            "secret": d["hmacSecret"], "refresh": d["refreshToken"],
            "uid": int(jwt_claims(d["accessToken"])["sub"])}

def check(actual, expected, name, detail=""):
    mark = "✅" if actual == expected else "❌"
    print(f"[{name}] {actual} {'== ' + str(expected) if mark=='❌' else ''} {mark} {detail}")
    return actual == expected
