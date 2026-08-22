#!/bin/bash
# my-xhs 防火墙规则（2026-08-12 更新版，对齐当前架构端口）
# 背景：云主机公网暴露面更大——建议同时做两层：① 腾讯云安全组（控制台，只开必要端口+来源IP）
#       ② 本脚本（主机 iptables 纵深防御，可选）
# 用法: bash setup-firewall.sh [ALLOW_IP]
#   ALLOW_IP = 微服务机 IP（默认需修改为实际 IP）

ALLOW_IP="${1:-请设置微服务机IP}"

# 当前架构端口（与 config/docker-compose.yml 对齐）
# MySQL(主/从) Redis(主/从/sentinel) RocketMQ(namesrv/broker/dashboard)
# Nacos ES(业务/SW) xxl-job SW(12800/1234/8080) Sentinel Prometheus VM Grafana
# logstash(15044/15045) alertmanager Kibana
# canal: serverMode=rocketMQ 时为 producer, 不监听 11111(tcp 端口无效);
#         仅 11110(admin)/11112(metrics) 监听, 无需放行 11111(2026-08-14 A-4 修正)
PORTS=(3306 3307 6379 6380 26379 9876 11911 18081 18848 19200 19201 18080 12800 1234 8080 8858 19090 8428 13000 15044 15045 19093 15601 11110 11112)

echo "=== 设置 my-xhs 防火墙规则 ==="
echo "允许来源: ${ALLOW_IP}"
echo "保护端口: ${PORTS[*]}"

for PORT in "${PORTS[@]}"; do
    # 先清除该端口的旧规则（避免重复）
    iptables -D INPUT -p tcp --dport ${PORT} -j DROP 2>/dev/null
    iptables -D INPUT -p tcp -s ${ALLOW_IP} --dport ${PORT} -j ACCEPT 2>/dev/null
    iptables -D INPUT -p tcp -s 127.0.0.1 --dport ${PORT} -j ACCEPT 2>/dev/null

    # 允许微服务机访问
    iptables -I INPUT -p tcp -s ${ALLOW_IP} --dport ${PORT} -j ACCEPT
    # 允许本机回环
    iptables -I INPUT -p tcp -s 127.0.0.1 --dport ${PORT} -j ACCEPT
    # 拒绝其他所有来源
    iptables -A INPUT -p tcp --dport ${PORT} -j DROP
done

echo "=== 规则已生效（抽样验证）==="
iptables -L INPUT -n --line-numbers | grep -E "dpt:(3306|6379|18848|19200)" | head -12
echo ""
echo "提示：规则仅本次生效（重启丢失）。持久化："
echo "  Debian/Ubuntu: apt install iptables-persistent && netfilter-persistent save"
echo "  CentOS: iptables-save > /etc/sysconfig/iptables"
echo "云主机还应在控制台配置安全组（仅放行必要端口给指定来源 IP）。"
