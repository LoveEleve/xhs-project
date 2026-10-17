---
name: security-toolkit
stage: 组件封装
description: 安全工具封装——脱敏/加密工具、输入校验框架、鉴权模板、日志脱敏、安全配置
---

# 安全工具封装（security-toolkit）

## 1. 职责

为项目生成安全基础设施代码，包括脱敏工具、加密工具、输入校验框架、鉴权中间件、日志脱敏、安全配置。**禁止在业务代码中手动处理敏感信息脱敏和鉴权逻辑**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/security-toolkit [desensitize|crypto|validation|auth|audit|all]` |
| 自动加载 | coding-skill 阶段始终加载（安全组件是每个项目的必选项） |

## 3. 前置条件

- 项目已识别技术栈
- 已确定目标包路径（按语言规范）
- 已确定安全需求等级（标准/高安全/合规要求）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `desensitize` | 脱敏工具 | 日志/API 响应中包含敏感字段 |
| `crypto` | 加密工具 | 需要存储密码/Token/密钥 |
| `validation` | 输入校验框架 | 存在外部输入未校验 |
| `auth` | 鉴权中间件 | 存在需要鉴权的 API 接口 |
| `audit` | 日志脱敏 + 安全配置 | 无安全配置或日志泄漏敏感信息 |
| `all`（默认） | 全部组件 | 首次引入安全基础设施 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/security/
├── DesensitizeUtil.java          — 脱敏工具
├── CryptoUtil.java               — 加密工具
├── validation/
│   ├── XssFilter.java            — XSS 过滤器
│   ├── SqlInjectionValidator.java
│   └── ParamValidator.java       — 参数校验器
├── auth/
│   ├── AuthMiddleware.java       — 鉴权中间件
│   ├── RequireAuth.java          — 鉴权注解
│   └── TokenUtil.java            — Token 工具
├── audit/
│   ├── LogDesensitizer.java      — 日志脱敏器
│   └── SecurityConfig.java       — 安全配置
└── config/
    └── application-security.yml

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `DesensitizeUtil`：手机号/邮箱/身份证/银行卡四种内置规则，支持自定义保留位和掩码字符
- `CryptoUtil`：AES-256-GCM（带认证），RSA-2048-OAEP，密码哈希用 BCrypt（cost >= 10 或 Argon2id），严禁使用 MD5/SHA1/DES/ECB
- XSS 过滤器：在请求入口处统一拦截，对输入做 HTML 转义（< -> &lt;，> -> &gt;），放过已有安全框架（如 Spring 的防 XSS）时跳过
- 参数校验器：字符串长度上限、禁止字符集、数字范围、文件上传白名单、URL 协议白名单、分页参数上限
- `AuthMiddleware`：在路由层统一拦截，从请求头提取 Token，校验签名+有效期，校验角色/权限，注入用户信息到上下文，不依赖每个 Controller 自己写鉴权逻辑
- `LogDesensitizer`：在日志输出层统一拦截（日志框架的 Layout/Formatter 扩展），配置化脱敏规则（哪些字段+哪种规则），不在业务代码中手动脱敏
- `SecurityConfig`：CORS（禁止开放所有域，限定来源+方法+max-age）、RateLimit（默认 100/s）、安全响应头（XSS-Protection/Content-Type-Options/Frame-Options/HSTS）

### Step 3: 生成测试

```
{basePackage}/security/
├── DesensitizeUtilTest.java
├── CryptoUtilTest.java
├── XssFilterTest.java
├── ParamValidatorTest.java
├── AuthMiddlewareTest.java
└── LogDesensitizerTest.java
```

测试要求：
- 脱敏：验证手机号/邮箱/身份证/银行卡四种规则，验证边界情况（空字符串/短字符串）
- 加密：验证 AES 加密解密一致性、RSA 加解密、BCrypt 哈希验证
- XSS 过滤：验证常见 XSS payload 被转义、验证合法 HTML 不被误杀
- 参数校验：验证长度上限、禁止字符、数字范围边界
- 鉴权：验证无效 Token 返回 401、权限不足返回 403、有效 Token 通过

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 密码存储使用 BCrypt/Argon2（非 MD5/SHA1）
   - [ ] 对称加密使用 GCM 模式（非 ECB）
   - [ ] 日志中敏感信息已脱敏（手机号、邮箱、身份证、银行卡）
   - [ ] 输入校验在入口处统一拦截（非每个方法单独校验）
   - [ ] 鉴权逻辑在中间件中统一处理（非每个 Controller 单独写）
   - [ ] CORS 配置了白名单（非开放所有域）

## 5. 输出文件清单

```
{targetPackage}/security/
├── DesensitizeUtil.{java|py|go|ts}
├── CryptoUtil.{java|py|go|ts}
├── validation/XssFilter.{java|py|go|ts}
├── validation/SqlInjectionValidator.{java|py|go|ts}
├── validation/ParamValidator.{java|py|go|ts}
├── auth/AuthMiddleware.{java|py|go|ts}
├── auth/RequireAuth.{java|py|go|ts}
├── auth/TokenUtil.{java|py|go|ts}
├── audit/LogDesensitizer.{java|py|go|ts}
├── audit/SecurityConfig.{java|py|go|ts}
├── config/application-security.yml
├── DesensitizeUtilTest.{java|py|go|ts}
├── CryptoUtilTest.{java|py|go|ts}
├── XssFilterTest.{java|py|go|ts}
├── ParamValidatorTest.{java|py|go|ts}
├── AuthMiddlewareTest.{java|py|go|ts}
└── LogDesensitizerTest.{java|py|go|ts}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 密码使用 BCrypt/Argon2
- ✅ 对称加密使用 GCM 模式
- ✅ 日志敏感信息已脱敏
- ✅ 输入校验在入口处统一拦截
- ✅ 鉴权在中间件统一处理
- ✅ CORS 配置了白名单
- ❌ 禁止使用 MD5/SHA1 存储密码
- ❌ 禁止使用 ECB 加密模式
- ❌ 禁止在业务代码中手动脱敏（应在输出层统一处理）
- ❌ 禁止 CORS 开放所有域（`Access-Control-Allow-Origin: *`）

## 7. 约束

- ❌ 不生成业务相关的鉴权逻辑（如"管理员才能删除订单"这类业务规则由用户在 Controller 中实现）
- ❌ 不引入未确认的加密库（项目中已有的优先复用，如 Java 用 javax.crypto 而非 BouncyCastle）
- ❌ 不修改已有的安全配置（只新增安全组件，不覆盖已有配置）
- ❌ 不生成证书/密钥管理方案（证书由安全团队或云平台管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
