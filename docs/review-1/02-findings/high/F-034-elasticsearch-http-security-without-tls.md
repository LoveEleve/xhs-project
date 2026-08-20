# F-034 Elasticsearch 开启认证但关闭 HTTP TLS，凭据可能明文传输

## 严重度

High

## 涉及文件

- `config/docker-compose.yml:385-397`
- `config/docker-compose.yml:422-434`
- `my-xhs-search/src/main/resources/application.yml:93-99`

## 现象

Elasticsearch 配置启用了 `xpack.security.enabled=true`，但同时明确关闭了 HTTP TLS：

```yaml
xpack.security.enabled=true
xpack.security.http.ssl.enabled=false
```

业务服务通过 HTTP Basic Auth 访问 ES，且 ES 使用 host 网络直接监听宿主机端口。

## 证据

1. 业务 ES：`docker-compose.yml:392-395`。
2. SkyWalking ES：`docker-compose.yml:429-432`。
3. search 服务通过 `http://21.130.247.89:19200` 配置 ES 地址，并提供用户名/密码：`my-xhs-search/src/main/resources/application.yml:93-99`。
4. F-033 已确认 host 网络使 ES 端口直接进入宿主机网络命名空间。

## 影响

1. 在非完全可信网络中，ES 用户名/密码和索引数据可能被窃听。
2. 凭据一旦被截获，可读取/修改搜索索引和日志链路数据。
3. 仅靠 Basic Auth 不提供传输机密性，安全组或内网隔离失效时风险扩大。

## 修复建议

1. 启用 Elasticsearch HTTP TLS，并在客户端配置 CA/证书校验。
2. 业务 ES 与 SkyWalking ES 使用独立凭据、最小权限用户。
3. 仅允许应用网段访问 ES 端口，禁止公网暴露。
4. 移除 compose healthcheck、脚本中的明文认证参数，改用 secrets。

## 是否需要补充验证

检查当前 ES 端口监听范围、网络抓包路径和客户端是否启用证书校验。