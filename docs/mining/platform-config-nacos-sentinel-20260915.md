# 平台 Nacos/Sentinel/网关配置实体（2026-09-15）

## 1. Nacos 配置（config/nacos/，仅 3 个文件）
- my-xhs-common.yaml：共享默认（datasource/redis 密码、6379），**无任何连接池/超时/线程池配置**；16 个 yml 以 shared-configs 引用且 refresh=true
- my-xhs-gateway.yaml：网关专属（JWT secret 有环境变量占位；hmac-secret 明文，P-D1 待办）
- my-xhs-redis.yaml：未挂到任何 shared-configs（仅导入命名空间后靠约定生效）
- 另有 `scripts/nacos-config-import.sh` 是**陈旧脚本**（8848 端口、旧 secret、导入了 my-xhs-rate-limit.yaml/my-xhs-degrade-switch.yaml 两个无人读取的 dataId）

## 2. Sentinel 规则（16 个 JSON，全部手工导入制品）
- 网关流控 15 条：全部 QPS 直限/快速失败——user 50、content 200、search 300、order 10、payment 5、inventory 30、analytics 100、counter 100、product 100、cart 50、coupon 30、home 50、notification 50、im 100、recommend 100（**旧版值**，与 yml metadata 的 500/300 不一致）
- 网关降级 12 条：慢调用比例（RT 阈值 500~3000ms、timeWindow 10s、minRequest 5、ratio 0.5）
- 服务内降级 14 个文件：资源名 `GET:/api/xxx/**`，GET/POST 成对，RT 300~3000ms
- **加载方式**：pom 引入 sentinel-datasource-nacos 但**无任何 datasource 配置**；服务端 degrade JSON 无代码加载器；实际生效=网关 RateLimitFilter 从路由 metadata 生成规则（缺省 100 QPS）；Bulkhead 只设系统属性（10 线程/20 队列），无真实规则
- Dashboard 8858，client port 逐服务 8719~8734

## 3. 网关配置细节
- **路由 16 条**（不是 17；`/ai-api/**` 旧路由已删，仅残留在 HMAC 白名单）
- 路由超时 connect 0.5~2s / response 2~10s；**xhs-ai 路由 response-timeout 31min**；metadata 超时需自定义 Filter 才生效，否则走 httpclient 全局（connect 2s/response 10s）
- httpclient：fixed 池 500 连接、max-idle 30s、max-life 45s（< 下游 keep-alive 60s，T-048）
- server：19000、Tomcat 300/30、8192 连接、accept 100、keep-alive 60s
- 鉴权：`hmac-enabled=false`；JWT 白名单 24 条、HMAC 白名单 57 条
- 灰度：`GRAY_PERCENT=10` 硬编码，仅打标签（GrayLoadBalancer 实例过滤**未实现**）
- CORS：myxhs.com/www/m 三域 + 内网 IP

## 4. 开关/降级项（20 个）
chaos.enabled=false；hmac-enabled=false；myxhs.dynamic.degrade.*（cache/mq/feign/db/search/recommend/feed/notification/hot-fallback，均 false）；SqlGuard 慢 SQL 200ms/连续 5 次/冷却 30s（硬编码，仅告警不阻塞）；Feign 重试全局 NEVER_RETRY（防重复下单）；readwrite.enabled（仅 inventory）；lock-watchdog 15s；停机 deregister-wait 10s、lifecycle 30s；mcp-tools-enabled=false；pay.type=mock；order.close.delay-level=16。
