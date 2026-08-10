# U01 — 注册 (POST /api/user/auth/register)

> 2026-08-08 | 链1-2 | user服务 | chaintest_c1(2085982901507301378)

## § 业务逻辑

校验captcha→BCrypt加密密码→MySQL INSERT t_user→消费captcha(Redis DEL)。Gateway whitelist免鉴权。状态: username唯一性校验→status=1(正常)。

## § ASCII 流转图

```
curl → Gateway:19000 (whitelist免JWT)
       → my-xhs-user:19001 (POST /api/user/auth/register)
         → UserService.register()
           → Redis GET captcha→校验
           → MySQL INSERT t_user (BCrypt password, status=1)
           → Redis DEL captcha key
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| 用户名 | chaintest_c1 ✅ |
| 注册成功 | code=200 ✅ |
| MySQL写入 | id=2085982901507301378, status=1 ✅ |
| captcha消费 | TTL=-2(已删除) ✅ |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 OK, X-Trace-Id: f4127de5... |
| MySQL | t_user: id=2085982901507301378, status=1, created_at=2026-08-08 14:54:14 ✅ |
| Redis | captcha key TTL=-2(消费后删除) ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 安全 | BCrypt密码加密存储 ✅ |
| 幂等 | 重名username→10002拒绝(非本测试) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id: f4127de5ab9446959b34c2605160ab57 ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; d=json.load(open('/tmp/cap.json')); print(d['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')
curl -s -X POST http://localhost:19000/api/user/auth/register -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_c1\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}"
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
