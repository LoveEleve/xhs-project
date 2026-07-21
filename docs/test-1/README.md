# MyXHS 项目文档

## 架构与方案

- [Redis 高可用方案](redis-ha.md) — 哨兵模式 / 集群模式 / 持久化策略

## 部署

- [K8s 部署指南](../k8s/README.md)

## 配置

- [Sentinel 规则管理](../config/sentinel/README.md)

## 基础设施

- ELK 日志平台：Kibana (15601) + Elasticsearch (19200) + Filebeat
- 全链路追踪：SkyWalking OAP (11800/12800)
- 监控：Prometheus (19090) + Grafana (3000)
- 配置中心：Nacos (18848)
- 消息队列：RocketMQ (19876)
