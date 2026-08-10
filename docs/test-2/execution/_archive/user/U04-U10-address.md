# U04-U10 + 地址 CRUD — 用户管理

## U04: GET /api/user/me — 当前用户信息

```
[curl]→GW:19000→user:19001→UserController.me(X-User-Id=10001)
    └ MySQL:13306 my_xhs_user.t_user SELECT WHERE id=10001
```

### curl
```bash
curl -s "http://localhost:19000/api/user/me" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, nickname=测试用户A | ✅ |
| MySQL | t_user id=10001, status=1 | ✅ |
| SW | ea78f55aabbd44b99eb1094393de9534 | ✅ |

---

## U05: GET /api/user/{userId}/info — 公开资料

```
[curl]→GW:19000→user:19001→getUserPublicInfo(/10001/info)→MySQL t_user SELECT
```

### curl
```bash
curl -s "http://localhost:19000/api/user/10001/info" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200 | ✅ |
| SW | d7b1dffecd964a13844dfcb68a2a8de6 | ✅ |

---

## U08/U09/U10: 拉黑/取消拉黑/列表

| 端点 | 方法 | 结果 | SW traceId |
|------|------|:--:|------|
| U08 拉黑 | POST /api/user/block/10002 | 200 | 2c4e8832 |
| U09 取消 | DELETE /api/user/block/10002 | 200 | d4caf41b |
| U10 列表 | GET /api/user/block/list | 200, 0 | ✅ |

---

## 地址 CRUD

| 端点 | 方法 | 结果 | SW traceId |
|------|------|:--:|------|
| list | GET /api/user/address/list | 200, 5 items | a7f60348 |
| create | POST /api/user/address | 200 | c129be6f |
| update | PUT /api/user/address/{id} | 200 | 4ef2a266 |
| set-default | PUT /api/user/address/{id}/default | 200 | 8dfef0d1 |
| delete | DELETE /api/user/address/{id} | 200 | 1d832d60 |

### 踩坑
- DTO 字段: `receiverName/receiverPhone/detailAddress`（非 name/phone/detail）
- Gateway HMAC 白名单需加 `/api/user/address/**` 等路径
