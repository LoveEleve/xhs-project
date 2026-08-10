# my-xhs 全链路测试

> 交接文档：[HANDOFF-20260808.md](HANDOFF-20260808.md)（v7 FINAL，106/155）
> 测试方法论：[methodology/TEST-METHODOLOGY.md](methodology/TEST-METHODOLOGY.md)（v2.0 分层验证 L1→L4）
> 执行计划：[plans/FULL-CHAIN-RETEST-PLAN.md](plans/FULL-CHAIN-RETEST-PLAN.md)（七链逐端点 curl + 155端点覆盖映射）
> 执行记录：[execution/](execution/)（按服务子目录，旧版归档至 `_archive/`）
> 业务分析：[service-analysis/](service-analysis/)（16服务代码审查文档，120+文件）
> 旧版交接：[HANDOFF-20260807-FINAL.md](HANDOFF-20260807-FINAL.md)（已被取代）

---

## 新 AI 阅读顺序

```
1. HANDOFF-20260808.md          ← 先读：基础设施 + 覆盖表 + §八 极简启动
2. methodology/TEST-METHODOLOGY.md  ← 二读：分层验证模型(L1→L4) + 9透镜
3. plans/FULL-CHAIN-RETEST-PLAN.md  ← 三读：逐端点 curl 命令 + 七层验证
4. execution/README.md          ← 执行索引(进度+文件清单)
5. execution/pitfalls.md        ← 踩坑速查(22项+17条预防)
```

---

## 快速启动

```bash
# 1. 确认 16 服务
ps aux | grep "my-xhs-" | grep java | grep -v grep | wc -l  # 应≈16

# 2. XXL-Job（jobGroup=1）
curl -c /tmp/xxl_cookie -s -X POST "http://21.130.247.89:18080/xxl-job-admin/login" \
  -d "userName=admin&password=123456"

# 3. JWT（注意：testuser密码已失效，用 mytestuser）
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')
TOKEN=$(curl -s http://localhost:19000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"mytestuser\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
echo "$TOKEN" > /tmp/test_token.txt
```

## 基础设施速查

| 工具 | 地址 | 凭据/说明 |
|------|------|------|
| Gateway | localhost:19000 | JWT `cat /tmp/test_token.txt` |
| MySQL | 21.130.247.89:13306-13309 | root/Xhs@2026#MySQL（远程无CREATE权限） |
| Redis | 21.130.247.89:**16379** | Xhs@2026#Redis（Sentinel主节点，非16381） |
| ES | 21.130.247.89:19200 | elastic/Xhs@2026#Elastic |
| Nacos | 21.130.247.89:18848 | nacos/nacos |
| XXL-Job | 21.130.247.89:18080 | admin/123456（jobGroup=1） |
| SkyWalking | 21.130.247.89:8080 | — |
| Prometheus | 21.130.247.89:19090 | — |
| Kibana | 21.130.247.89:15601 | elastic/Xhs@2026#Elastic |
| Admin Token | Header: X-Admin-Call | `my-xhs-admin-token-2026` |
| Test User | mytestuser / Test@123456 | ID: 2085927845755985922 |
