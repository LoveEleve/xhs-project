# U02 — 验证码 (GET /api/user/auth/captcha)

> 2026-08-08 | 链1-1 | user服务 | 公开端点(Gateway whitelist免鉴权)

## § 业务逻辑

生成32位hex captchaKey + base64 PNG图片，code值写入Redis `myxhs:user:captcha:{key}`(TTL=300s)（安全设计，不写日志）。状态机: 生成→存储→注册/登录消费后DEL。测试脚本从Redis读取code（见交接文档§5.2）。

## § ASCII 流转图

```
curl → Gateway:19000 (whitelist免JWT+免HMAC)
       → my-xhs-user:19001 (GET /api/user/auth/captcha)
         → CaptchaService.generateCaptcha()
           → Redis: SETEX myxhs:user:captcha:{key} {code} 300
           → 响应: captchaKey + captchaImage(base64 PNG)
           (code 从 Redis GET myxhs:user:captcha:{key} 读取，不写日志)
```

## § 业务链验证

| 检查项 | 结果 |
|------|:--:|
| captchaKey生成 | ✅ 32位hex |
| captchaImage生成 | ✅ base64 PNG(含"iVBOR"头) |
| 日志输出 | ✅ grep可提取code值 |
| Redis存储 | ✅ code="G3UH", TTL=300s |

## § 数据验证 (L2)

| 层 | 结果 |
|------|:--:|
| HTTP | 200 OK, X-Trace-Id: 3e967ec0... |
| Redis | key=myxhs:user:captcha:{key}, value="G3UH", TTL=300s ✅ |

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 性能 | 响应无异常延迟 ✅ |
| 安全 | 公开端点(whitelist)，无鉴权风险；验证码图片base64编码 ✅ |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SkyWalking | X-Trace-Id: 3e967ec0ad514830a740cdcdaff5dd76 ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/user/auth/captcha
```

## § 踩坑

无。
| Prometheus | GET user:19001/actuator/prometheus ✅ |
| Kibana | traceId 日志可查 ✅ |
