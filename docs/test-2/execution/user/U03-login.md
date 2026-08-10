# U03 — 登录 (POST /api/user/auth/login)

> 2026-08-08 | 链1-3 | user服务 | chaintest_c1(2085982901507301378)

## § 业务逻辑

验证码校验→BCrypt密码匹配→生成JWT token pair→Redis写access(30min)+refresh(7天)+hmac secret→MySQL更新last_login_time。单设备登录: 覆盖旧token。状态: 新token→合法用户身份→后续所有端点使用。

## § ASCII 流转图

```
curl → Gateway:19000 (whitelist免JWT)
       → my-xhs-user:19001 (POST /api/user/auth/login)
         → UserService.login()
           → Redis GET captcha→校验
           → MySQL SELECT t_user→BCrypt匹配
           → TokenService.generateTokenPair()
             → Redis SET myxhs:user:token:access:{userId} TTL=1800s
             → Redis SET myxhs:user:token:refresh:{userId} TTL=604800s
           → MySQL UPDATE last_login_time
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| JWT alg | HS256 ✅ |
| sub映射 | 2085982901507301378 ✅ |
| token type | access ✅ |
| 过期 | 1800s(30min) ✅ |
| 单设备覆盖 | 旧token key被覆盖 ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 OK |
| Redis access | myxhs:user:token:access:2085982901507301378 TTL=1794s ✅ |
| Redis refresh | myxhs:user:token:refresh:2085982901507301378 TTL=604794s(7天) ✅ |
| Redis hmac | myxhs:user:hmac:secret:{userId} ✅ |
| MySQL | last_login_time更新 ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 安全 | BCrypt+JWT+HMAC三层 ✅; 单设备覆盖防token泄漏 ✅ |
| 幂等 | captcha消费后DEL防复用 ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | traceId captured ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; d=json.load(open('/tmp/cap.json')); print(d['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')
TOKEN=$(curl -s http://localhost:19000/api/user/auth/login -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_c1\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
echo "$TOKEN" > /tmp/test_token.txt
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
