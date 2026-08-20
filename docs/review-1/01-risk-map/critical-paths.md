# critical-paths

## 1. 目的

先固定需要下钻的关键路径。后续每条路径都要从入口一路追到状态落库、异步传播、读模型和失败恢复。

## 2. 身份建立与传递

```text
客户端
  -> gateway JWT/HMAC
  -> GatewayAuthFilter
  -> X-User-Id / X-User-Role
  -> 业务服务
  -> internal/admin 或数据访问
```

### 必查点

1. 外部 header 是否在 gateway 被覆盖
2. 未登录路径是否真的 401
3. 白名单是否扩大了真实权限
4. 业务服务是否把 header 当成绝对可信
5. token 撤销是否对所有入口生效
6. 角色校验是否在关键写操作上闭环

## 3. 下单到履约

```text
商品/SKU
  -> cart
  -> inventory reserve
  -> order create
  -> payment callback
  -> order state transition
  -> inventory confirm/release
  -> notification / analytics / search projection
```

### 必查点

1. 下单与预扣的先后关系
2. 重试时是否重复扣库存
3. 支付回调是否可重放
4. 订单状态是否可能跳过中间态
5. 取消/退款时库存与优惠券是否回收
6. 跨分片查询与补偿是否使用同一用户路由

## 4. 内容发布到分发

```text
content write
  -> event/outbox
  -> counter / analytics
  -> search index
  -> home feed
  -> notification / im
```

### 必查点

1. 主数据提交与事件发布是否绑定
2. 删除事件是否能阻断晚到消息
3. 搜索索引是否可能重新写入已删除内容
4. counter/analytics 是否读取同一权威源
5. feed 合并与分页是否会丢条或重复
6. 同一事件多消费者是否存在时序竞争

## 5. AI 诊断请求

```text
frontend /ai-api
  -> gateway route/auth/rate-limit
  -> ai-app controller
  -> ai-tools / database / MCP
  -> SSE stream
  -> conversation/run state
```

### 必查点

1. route rewrite 后端路径是否一致
2. X-User-Id/X-User-Role 是否可信且完整
3. 会话与 run 是否绑定当前用户
4. 工具是否能跨越用户权限边界
5. SSE timeout、取消、重试是否正确
6. MCP 是否仅内网且具备最小权限
7. 失败时是否会重复触发高成本工具调用

## 6. 服务重启与运行态

```text
修改代码/配置
  -> Maven package
  -> restart-service.sh 或自定义启动
  -> PID/端口/健康检查
  -> 日志/监控
  -> 实际业务探活
```

### 必查点

1. PID 文件是否对应真实进程
2. 端口是否被旧实例占用
3. 健康检查是否误命中旧实例
4. 启动失败是否被脚本判定为成功
5. 新 jar 是否真的被加载
6. 配置来源是否与预期一致

## 7. 首轮路径优先级

| 顺序 | 路径 | 原因 |
|---|---|---|
| 1 | 身份建立与传递 | 全系统入口，影响面最大 |
| 2 | 下单到履约 | 直接涉及资金、库存与对账 |
| 3 | 内容发布到分发 | 异步传播面最广，脏数据风险高 |
| 4 | AI 诊断请求 | 新增跨域入口，边界尚未长期稳定 |
| 5 | 服务重启与运行态 | 直接影响修复是否真实生效 |
