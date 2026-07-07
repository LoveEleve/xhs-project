# Nacos 配置管理

## 导入配置
```bash
bash scripts/nacos-config-import.sh
```

环境变量：
- `NACOS_URL` — Nacos 服务地址，默认 `http://localhost:8848`
- `NACOS_NAMESPACE` — Nacos 命名空间，默认 `my-xhs`

## 配置列表

| Data ID | Group | 说明 |
|---------|-------|------|
| my-xhs-gateway.yaml | DEFAULT_GROUP | Gateway 安全配置（JWT secret、HMAC secret） |
| my-xhs-rate-limit.yaml | DEFAULT_GROUP | 各服务限流 QPS 阈值 |
| my-xhs-degrade-switch.yaml | DEFAULT_GROUP | 降级开关（payment、inventory、search 等） |
| my-xhs-redis.yaml | DEFAULT_GROUP | Redis 公共配置（密码等） |

## 本地 fallback 配置

各服务 application.yml 中使用 `${config.key:default}` 占位符格式引用 Nacos 配置，本地保留 fallback 值确保 Nacos 不可用时服务仍可启动。

示例：
```yaml
spring:
  data:
    redis:
      password: ${spring.data.redis.password:Xhs@2026#Redis}
```
