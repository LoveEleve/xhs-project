# my-xhs-user 模块总览

## 1. 当前模块定位

`my-xhs-user` 是用户域核心微服务，承载认证、用户资料、地址和用户关系基础能力，是几乎所有业务链的身份与用户资料来源。

核心职责：
- 验证码生成与一次性校验
- 用户注册、登录、刷新 Token、注销、改密
- 用户资料与公开资料查询/更新
- 收货地址增删改查与默认地址切换
- 用户屏蔽列表
- Redis Token、黑名单、登录失败控制和用户缓存
- 通过 RocketMQ 消费缓存删除兜底消息

## 2. 在整体业务链的位置

```text
客户端
  -> Gateway
  -> user
      -> MySQL: t_user / t_user_address
      -> Redis: captcha / token / blacklist / user cache / login lock
      -> RocketMQ: cache eviction fallback
  -> 下游服务通过 userId 使用用户上下文
```

用户服务不是简单 CRUD：注册、登录、Token、缓存、锁和地址默认状态共同构成长期状态系统。

## 3. 主要业务分组

- G1：认证 / 用户 / 关注基础
- G2：内容与社交依赖用户资料、屏蔽关系
- G3：购物车和交易依赖用户身份、默认地址
- G5：订单依赖地址、用户状态
- G7：通知/IM/计数依赖用户关系和用户 ID

## 4. 分析范围

本轮覆盖：
- 顶层构建文件：`Dockerfile`、`pom.xml`
- `src/main/java` 全部 27 个 Java 文件
- `src/main/resources` 全部 3 个配置文件
- `src/test/java` 全部 3 个测试文件
- 候选文件总数：35 个

其中“已读取”不等于“运行验证通过”：测试源码当前存在编译/运行阻断，真实数据库 schema、主从路由、Redis 和 RocketMQ 行为仍需单独确认。

`target/` 仅作为构建产物，不纳入源码逻辑分析。
