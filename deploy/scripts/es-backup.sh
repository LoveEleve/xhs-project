#!/bin/bash
# ES Snapshot 备份脚本
# crontab: 0 3 * * * /opt/scripts/es-backup.sh

ES="http://21.91.124.110:19200"
REPO="my_backup"
SNAPSHOT="snapshot_$(date +%Y%m%d)"
RETENTION=7

# 删除旧快照
for old in $(curl -s "$ES/_snapshot/$REPO/_all" | python3 -c "import json,sys;[print(s['snapshot']) for s in json.load(sys.stdin).get('snapshots',[])]" 2>/dev/null); do
    echo "清理: $old"
    curl -s -X DELETE "$ES/_snapshot/$REPO/$old" > /dev/null 2>&1
done

# 创建新快照
curl -s -X PUT "$ES/_snapshot/$REPO/$SNAPSHOT?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{"indices":"note_index,product_index","ignore_unavailable":true}'

echo "$(date): snapshot=$SNAPSHOT"
