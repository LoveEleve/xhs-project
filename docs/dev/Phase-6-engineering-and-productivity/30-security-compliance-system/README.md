# 安全合规体系

> 所属维度：安全合规 | 开发阶段：Phase-6 | 涉及服务：Gateway + 全部业务服务

---

## 🎯 一、安全体系总览

### 1.1 接口安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 请求签名 | HMAC-SHA256(timestamp+nonce+body+secretKey) | 防篡改+防重放，Gateway GlobalFilter校验 |
| 认证鉴权 | JWT双Token(Access 30min + Refresh 7d) | 未登录请求拦截在Gateway，不打到业务服务 |
| 接口限频 | @RateLimit + Sentinel | 防暴力破解、防恶意刷接口 |
| CORS跨域 | Gateway CorsFilter | 前端跨域，限制允许的域名 |
| 防重放攻击 | timestamp 5分钟过期 + nonce Redis去重 | 同一请求不能重复提交 |

### 1.2 数据安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 密码存储 | BCrypt慢哈希 | 防彩虹表破解，即使DB泄露也无法还原明文 |
| 敏感信息脱敏 | 返回DTO中手机号/邮箱打码 | 前端展示脱敏，防信息泄露，合规要求 |
| SQL注入防护 | MyBatis-Plus参数化查询 + 代码Review | 所有SQL必须参数化，禁止拼接用户输入 |
| XSS防护 | 前端输入转义 + 后端过滤HTML标签 | 笔记内容/评论中不能注入脚本 |
| CSRF防护 | JWT Token + SameSite Cookie | 防跨站请求伪造 |
| 文件上传安全 | 文件类型白名单 + 大小限制(5MB) | 防上传恶意脚本/超大文件 |

### 1.3 管理后台安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 网络隔离 | Admin服务只在内网暴露 | 外网无法直接访问管理后台 |
| RBAC权限 | 角色→菜单权限映射 | 不同角色看到不同功能，防止越权 |
| 操作审计 | 所有管理操作记录审计日志 | 操作可追溯，出问题可定位到人 |
| 二次确认 | 删除/下架等敏感操作需二次确认 | 防误操作 |

---

## 🏗️ 二、核心实现

### 2.1 HMAC签名流程

```
客户端：
1. 生成timestamp(当前时间戳)和nonce(随机字符串)
2. 拼接签名串：timestamp + nonce + body + secretKey
3. 计算签名：HMAC-SHA256(签名串)
4. 请求Header携带：X-Timestamp, X-Nonce, X-Signature

Gateway校验：
1. 检查timestamp是否在5分钟内 → 超时拒绝(防重放)
2. 检查nonce是否在Redis中存在 → 存在拒绝(防重放)
3. 用相同规则计算签名 → 比对是否一致(防篡改)
4. 校验通过 → nonce写入Redis(TTL=5分钟)
```

### 2.2 JWT双Token机制

```
登录成功 → 签发AccessToken(30min) + RefreshToken(7d)
          ↓
正常请求 → Header携带AccessToken → Gateway解析+校验
          ↓
AccessToken过期 → 用RefreshToken换新Token → 旧RefreshToken失效
          ↓
RefreshToken过期 → 重新登录
          ↓
修改密码 → 旧Token的jti加入Redis黑名单 → 强制重新登录
```

### 2.3 敏感信息脱敏

```java
// 手机号脱敏：138****8000
public static String maskPhone(String phone) {
    return phone.substring(0, 3) + "****" + phone.substring(7);
}

// 邮箱脱敏：t***@gmail.com
public static String maskEmail(String email) {
    int atIndex = email.indexOf('@');
    return email.charAt(0) + "***" + email.substring(atIndex);
}
```

---

## 📋 三、合规要求

| 合规项 | 说明 | my-xhs落地 |
|--------|------|-------------|
| 内容审核 | UGC内容必须审核后才能发布 | 笔记状态机(草稿→待审核→已发布) |
| 敏感词过滤 | 10万词库DFA过滤 | 评论/笔记内容发布前过滤 |
| 隐私保护 | 用户数据最小化收集、脱敏展示 | 手机号/邮箱脱敏，地址打码 |
| 数据留存 | 按法规要求留存日志和订单 | 订单3年、日志90天 |

---

## ⚖️ 四、方案对比

### 4.1 签名方案对比

| 维度 | HMAC-SHA256 | RSA签名 | AES加密 |
|------|------------|---------|---------|
| 性能 | 高(对称) | 低(非对称) | 高(对称) |
| 安全性 | 高(防篡改) | 最高(不可否认) | 中(仅加密) |
| 复杂度 | 低 | 高(证书管理) | 低 |
| 适用场景 | API接口签名 | 支付/金融 | 数据传输加密 |

**最终选择**：HMAC-SHA256 — 性能好、安全性够用、实现简单，微信/支付宝开放平台标配。

---

## 🐛 五、踩坑记录

### 5.1 {待开发时填写}

- **现象**：
- **原因**：
- **解决**：
- **教训**：

---

## 🎤 六、面试考察点

### Q1: 接口安全怎么保证的？

**推荐回答思路**：

> 1. "四层防护：HMAC签名防篡改+防重放、JWT鉴权、@RateLimit防刷、CORS限制域名"
> 2. "签名用HMAC-SHA256，timestamp+nonce+body+secretKey，Gateway GlobalFilter统一校验"
> 3. "防重放：timestamp 5分钟过期 + nonce Redis去重"

### Q2: 密码怎么存储的？为什么用BCrypt？

**推荐回答思路**：

> 1. "BCrypt慢哈希，加盐+多轮计算，即使DB泄露也无法还原明文"
> 2. "vs MD5/SHA256：太快了，每秒能算数十亿次，暴力破解成本极低"
> 3. "BCrypt每秒只能算几千次，暴力破解成本极高"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §11 | 安全合规完整方案 |
| 📄 02-模块详细设计.md | §2.2 | Gateway安全能力 |
| 📄 02-模块详细设计.md | §1.2 | @RateLimit注解实现 |
