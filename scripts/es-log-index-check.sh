#!/bin/bash
# 每日检查：若日志索引不是 replicas=0（单节点）则修正 + 重挂 ILM
AUTH="elastic:Xhs@2026#Elastic"; ES="http://192.168.0.142:19200"
for idx in $(curl -s -u "$AUTH" "$ES/_cat/indices/myxhs-logs-*?h=index"); do
  r=$(curl -s -u "$AUTH" "$ES/$idx/_settings" | python3 -c "import json,sys;d=json.load(sys.stdin);print(list(d.values())[0]['settings']['index'].get('number_of_replicas','0'))")
  if [ "$r" != "0" ]; then
    curl -s -u "$AUTH" -XPUT "$ES/$idx/_settings" -H 'Content-Type: application/json' -d '{"index":{"number_of_replicas":0}}' >/dev/null
    echo "$(date '+%F %T') 修正 $idx replicas $r -> 0"
  fi
done
