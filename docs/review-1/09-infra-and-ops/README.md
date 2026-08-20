# 09-infra-and-ops 首轮审查记录

## 已确认

### F-014：重启脚本健康检查假阳性

脚本按端口 health 判断成功，不校验新 PID；旧实例占端口时会误报新实例 UP。

### F-018/F-019：配置与凭据漂移

- common Redis 配置有公开 fallback。
- 多服务 yml、sharding 配置、测试脚本直接提交基础设施密码与 JWT/HMAC secret。
- IM 还有独立 JWT fallback（F-017）。

## 已复核为非新增 finding

1. `start-all.sh` 当前会生成 `.secrets/tokens.env` 随机 token，并拒绝空 token，优于旧的明文启动脚本；但仓库历史和其他配置仍存在泄漏风险。
2. `stop-all.sh` 的宽泛残留进程清理存在误杀风险，但需要结合同机其他 Java 进程部署模型才能升级为独立 finding，暂记为待确认。
3. Nacos 配置文件使用环境变量承载 token/identity，但当前快照未能仅凭仓库确认运行时是否已开启完整 Nacos server auth，暂不升级为 finding。

## 继续核查

1. Nacos 实际运行配置与服务端 dataId 的覆盖优先级。
2. 部署脚本是否把所有服务暴露到公网，而安全组只保护 gateway。
3. Docker/K8s 与本地 `start-all.sh` 的配置是否存在互相覆盖。
4. 日志/监控是否会记录 Authorization、token、password 或 HMAC secret。
