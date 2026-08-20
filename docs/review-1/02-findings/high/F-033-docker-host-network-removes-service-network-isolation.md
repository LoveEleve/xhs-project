# F-033 Docker 全部服务使用 host 网络，服务间网络隔离完全依赖宿主机防火墙

## 严重度

High

## 涉及文件

- `config/docker-compose.yml:115-953`
- `setup-firewall.sh:11-33`
- `config/deploy-cloud/DEPLOY-README.md:77-84`

## 现象

compose 中几乎所有中间件容器都使用 `network_mode: host`。这意味着容器服务直接监听宿主机网络命名空间，不经过 Docker bridge 网络，也没有容器级端口隔离。

## 证据

1. MySQL、Redis、Sentinel、RocketMQ、ES、Nacos、XXL-Job、监控等服务都声明 `network_mode: host`。
2. compose 中没有通过 bridge 网络或显式端口映射限制服务可达范围。
3. 部署文档承认云主机公网暴露面较大，并把安全组/iptables 作为关键防护：`DEPLOY-README.md:77-84`。
4. `setup-firewall.sh` 的规则不持久化，见 F-029。

## 影响

1. 任意宿主机可达端口都直接暴露在同一网络命名空间，Nacos/Redis/MySQL/ES 等管理面与业务面没有容器级隔离。
2. 任意一个防火墙规则丢失、配置错误或安全组放行过宽，所有中间件端口会同时暴露。
3. 某个容器被攻破后，访问其他服务不再需要跨容器网络边界。
4. F-001/F-002/F-017/F-018/F-026/F-029 的风险被整体放大。

## 修复建议

1. 默认使用 Docker bridge 网络，只对 gateway/必要入口发布端口。
2. 中间件通过内部网络访问，业务服务通过受控网络或服务发现访问。
3. 如果必须使用 host 网络，必须把安全组、iptables/nftables、启动校验和持久化纳入部署阻断条件。
4. 对外暴露面做端口清单自动校验，禁止出现未授权监听端口。

## 是否需要补充验证

需要从宿主机执行 `ss -lntp`，核对所有监听端口与安全组规则；并从非允许来源验证中间件端口不可达。