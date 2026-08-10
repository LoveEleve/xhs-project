# my-xhs-user 业务逻辑分析

## 一、认证状态机

```
未认证 → [获取验证码] → [登录] → 已认证
                                    ↓
                              [Token过期/刷新] → 新Token
                              [登出] → Token黑名单
                              [改密码] → 全部Token失效
```

## 二、登录异常路径

```
正常: 验证码OK → 用户名存在 → 密码正确 → 返回Token
异常:
  验证码错误 → "验证码错误" (验证码key已删除，二次使用也失败)
  用户名不存在 → "用户不存在"
  密码错误: 失败1-4次 → "密码错误" + Redis计数+1
           失败第5次 → "密码错误" + Redis计数+1 → 写USER_LOGIN_LOCK 15分钟
           锁定后尝试 → "账号已锁定，请15分钟后重试"
```

## 三、Token生命周期

```
生成: 登录/刷新 → access(30min) + refresh(7d)
使用: 每次请求 GatewayAuthFilter 解析JWT → 注入X-User-Id
刷新: access即将过期 → 用refresh换新access(旧Token入黑名单)
登出: access+refresh 双入黑名单
改密: 所有活跃Token全部入黑名单 → 强制重新登录
```

## 四、地址管理约定

```
上限: 每用户最多20个地址
默认: 首个地址自动设为默认
      设置新默认 → 旧默认取消 → 新默认设置
      删除默认地址 → 剩余首个自动升级为默认
缓存: 默认地址缓存30min，修改时清除缓存
```

## 五、用户信息更新

```
更新字段: 昵称、性别、生日、签名、头像URL
不可更新: 用户名、手机号(需唯一性校验，当前未开放接口)
流程:    UPDATE t_user → delayDoubleDelete Redis缓存 → MQ CACHE_EVICT兜底 → 查DB返回最新
原因:    写DB后立即读可能命中旧Redis缓存 → 延迟双删确保一致性
```

## 六、登录失败计数

```
Key:    myxhs:user:login:fail:{username}
计数:   每次密码错误 INCR，不设TTL（成功时DEL）
阈值:   5次 → 写 myxhs:user:login:lock:{username} TTL=15min
重置:   登录成功后 DEL fail计数 + DEL lock(如有)
```

## 七、屏蔽（Block）管理

```
屏蔽:    POST /block/{targetUserId} → 校验不能屏蔽自己 → SADD Redis Set(365天TTL)
取消屏蔽: DELETE /block/{targetUserId} → SREM → 幂等（已被屏蔽或未屏蔽都返回ok）
列表:   GET /block/list → SMEMBERS取ID集合 → MySQL IN查询用户名/昵称/头像
数据流: 全部在Redis Set完成，MySQL仅列表补充显示（INSERTS/查询，不写Redis外）

关键规则:
  - 不能屏蔽自己（userId == targetUserId → 拒绝）
  - 取消屏蔽幂等（SREM不存在的元素返回ok）
  - 屏蔽列表不删除物理数据，仅Redis标记
```
