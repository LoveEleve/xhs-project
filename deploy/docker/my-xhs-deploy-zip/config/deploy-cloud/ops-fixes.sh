#!/bin/bash
# ============================================================
# 部署后运维修复合集（中间件机执行，分步骤可选）
# 覆盖：P-D7 Kibana 密码 / P-D2 从库重建 / P-D22 库存补偿表 / P-D10 命名空间清理
# 用法: bash ops-fixes.sh [kibana|replica|compensation|cleanup|all]
# ============================================================
set -e
STEP="${1:-all}"
MYSQL_CMD="mysql -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL'"

fix_kibana() {
    echo "=== [P-D7] 重置 kibana_system 密码并重启 Kibana ==="
    curl -s -u elastic:Xhs@2026#Elastic -X POST 'http://127.0.0.1:19200/_security/user/kibana_system/_password' \
        -H 'Content-Type: application/json' -d '{"password":"Xhs@2026#KibanaSystem"}'
    echo ""
    docker restart my-xhs-kibana
    sleep 10
    curl -s -o /dev/null -w "Kibana /api/status → HTTP %{http_code}\n" http://127.0.0.1:15601/api/status || echo "Kibana 未就绪（稍后重试）"
}

fix_replica() {
    echo "=== [P-D2] 重建 MySQL 从库（勿跑 init-all.sql！数据由主库 dump 同步）==="
    echo "→ 步骤1: 主库全量 dump（保留 GTID 位置）"
    mysqldump -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' --all-databases --single-transaction --source-data=2 --routines --triggers > /data/backups/rebuild-slave-dump.sql
    echo "→ 步骤2: 停止从库容器并清数据卷"
    docker stop my-xhs-mysql-slave && docker rm my-xhs-mysql-slave
    docker volume rm mysql-slave-data 2>/dev/null || true
    echo "→ 步骤3: 重新创建从库容器（挂载 init-replication.sql 会执行 CHANGE MASTER + START SLAVE）"
    docker compose -f /data/workspace/my-xhs-deploy-zip/docker-compose.yml up -d mysql-slave
    echo "→ 步骤4: 验证复制状态"
    mysql -h127.0.0.1 -P3307 -uroot -p'Xhs@2026#MySQL' -e "SHOW REPLICA STATUS\G" | grep -E "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind"
}

fix_compensation() {
    echo "=== [P-D22] t_inventory_compensation schema 对齐（补列/删列/索引）==="
    eval "$MYSQL_CMD" my_xhs_inventory -e "
        ALTER TABLE t_inventory_compensation
          ADD COLUMN fail_reason VARCHAR(512) NOT NULL DEFAULT '' AFTER quantity,
          ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER status,
          DROP COLUMN action,
          ADD INDEX idx_status_retry_created (status, retry_count, created_at);
    " || echo "⚠️ ALTER 失败：请先备份旧表数据并核对表结构（可能已改过）"
    eval "$MYSQL_CMD" my_xhs_inventory -e "DESC t_inventory_compensation;"
}

fix_cleanup() {
    echo "=== [P-D10] 清理 sca-lab-dev 命名空间（确认无引用后）==="
    echo "→ 命名空间内配置见 Nacos 控制台 sca-lab-dev；删除语句："
    echo "  DELETE FROM nacos_config.config_info WHERE tenant_id='sca-lab-dev';"
    echo "  DELETE FROM nacos_config.tenant_info WHERE tenant_namespace='sca-lab-dev';"
    echo "（若 seata_lab 库无引用：DROP DATABASE seata_lab;）"
}

case "$STEP" in
    kibana)        fix_kibana ;;
    replica)       fix_replica ;;
    compensation)  fix_compensation ;;
    cleanup)       fix_cleanup ;;
    all)           fix_kibana; fix_compensation; fix_cleanup; echo "注：从库重建(fix_replica)需停机窗口，单独执行: bash ops-fixes.sh replica" ;;
    *) echo "用法: $0 [kibana|replica|compensation|cleanup|all]"; exit 1 ;;
esac
echo "✅ 完成"
