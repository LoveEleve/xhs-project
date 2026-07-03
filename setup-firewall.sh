#!/bin/bash
# my-xhs 防火墙规则：仅允许开发机 21.214.97.212 访问中间件端口

ALLOW_IP="21.214.97.212"
PORTS=(13306 13307 13308 13309 16379 18848 18849 18850 9876 11911 19200 18080 19090 13000 18081)

echo "=== 设置 my-xhs 防火墙规则 ==="
echo "允许来源: ${ALLOW_IP}"
echo "保护端口: ${PORTS[*]}"

for PORT in "${PORTS[@]}"; do
    # 先清除该端口的旧规则（避免重复）
    iptables -D INPUT -p tcp --dport ${PORT} -j DROP 2>/dev/null
    
    # 允许开发机访问
    iptables -I INPUT -p tcp -s ${ALLOW_IP} --dport ${PORT} -j ACCEPT
    
    # 允许本机回环
    iptables -I INPUT -p tcp -s 127.0.0.1 --dport ${PORT} -j ACCEPT
    
    # 拒绝其他所有来源
    iptables -A INPUT -p tcp --dport ${PORT} -j DROP
done

echo "=== 规则已生效 ==="
iptables -L INPUT -n --line-numbers | grep -E "spt|${ALLOW_IP}|DROP" | head -40
