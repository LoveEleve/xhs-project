#!/bin/bash
# ============================================================
# P-D14: ES 业务日志索引 ILM（30 天删除）+ 索引模板（单节点副本=0）
# 在中间件机执行（需 curl 访问 19200）
# 用法: bash apply-ilm.sh [ES_HOST]
# ============================================================
set -e
ES_HOST="${1:-127.0.0.1:19200}"
AUTH="elastic:Xhs@2026#Elastic"

echo "=== [1/2] 创建 ILM policy myxhs-logs-policy（30d delete）==="
curl -s -u "$AUTH" -X PUT "http://$ES_HOST/_ilm/policy/myxhs-logs-policy" -H 'Content-Type: application/json' -d '{
  "policy": {
    "phases": {
      "hot": { "min_age": "0ms", "actions": { "set_priority": { "priority": 100 } } },
      "delete": { "min_age": "30d", "actions": { "delete": {} } }
    }
  }
}'
echo ""

echo "=== [2/2] 创建索引模板 myxhs-logs-template（myxhs-logs-* 挂 policy + 副本0）==="
curl -s -u "$AUTH" -X PUT "http://$ES_HOST/_index_template/myxhs-logs-template" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["myxhs-logs-*"],
  "template": {
    "settings": {
      "number_of_replicas": 0,
      "number_of_shards": 1,
      "lifecycle": { "name": "myxhs-logs-policy" }
    }
  }
}'
echo ""

echo "=== 验证 ==="
curl -s -u "$AUTH" "http://$ES_HOST/_ilm/policy/myxhs-logs-policy" | head -c 300
echo ""
echo "✅ 完成。已存在的旧索引 myxhs-logs-*.2026.* 不会自动套用 policy，可手动："
echo "   POST http://$ES_HOST/myxhs-logs-*/_ilm/migrate_to_data_tiers  （或等待滚动到新索引）"
