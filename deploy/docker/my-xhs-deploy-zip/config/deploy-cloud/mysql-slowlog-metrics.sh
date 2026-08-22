#!/bin/bash
# MySQL 慢查询指标采集 (textfile collector, 与 rocketmq-metrics.sh 同模式)
# 数据源: mysql.slow_log 表 (log_output=TABLE, 慢查询实时入表)
# 归一化 SQL 取 md5 -> 聚合输出到 node-exporter textfile 目录
# 指标:
#   mysql_slow_query_total{sql_hash,db}                      累计慢查询次数 (counter)
#   mysql_slow_query_total_time_seconds{sql_hash,db}         累计慢查询耗时 (counter)
#   mysql_slow_query_max_query_time_seconds{sql_hash}        单条最大耗时   (gauge)
#   mysql_slow_query_top{sql_hash,db,sample}                 归一化 SQL 样例 (gauge=1)
#   mysql_slow_query_up                                      采集成功=1
OUT=/data/rocketmq-textfile/mysql-slowlog.prom
TMP=${OUT}.tmp
: > "$TMP"

docker exec my-xhs-mysql mysql -uroot -p'Xhs@2026#MySQL' --default-character-set=utf8mb4 -N -e \
  "SELECT TIME_TO_SEC(query_time) + MICROSECOND(query_time)/1000000, COALESCE(NULLIF(db,''),'unknown'), sql_text FROM mysql.slow_log WHERE sql_text IS NOT NULL AND sql_text <> '';" 2>/dev/null \
| while IFS=$'\t' read -r qtime db sql; do
  [ -z "$sql" ] && continue
  # 归一化: 去字符串字面量/数字/timestamp, 压缩空格
  norm=$(printf '%s' "$sql" | sed -E "s/'[^']*'/?/g; s/\"[^\"]*\"/?/g; s/[0-9]+/N/g; s/[[:space:]]+/ /g")
  [ -z "$norm" ] && continue
  h=$(printf '%s' "$norm" | md5sum | cut -d' ' -f1)
  printf '%s\t%s\t%s\t%s\n' "$qtime" "$db" "$h" "$norm"
done > "$TMP.tsv"

if [ ! -s "$TMP.tsv" ]; then
  echo "mysql_slow_query_up 1" > "$OUT"
  chmod 644 "$OUT"
  rm -f "$TMP.tsv"
  exit 0
fi

# 聚合输出
awk -F'\t' '
{
  h=$3
  count[h]++; time[h]+=$1
  if ($1 > max[h]) max[h]=$1
  db[h]=$2
  sample[h]=$4
}
END {
  for (h in count) {
    if (length(sample[h]) > 200) sample[h]=substr(sample[h],1,200) "..."
    gsub(/"/, "\\\"", sample[h])
    printf "mysql_slow_query_total{sql_hash=\"%s\",db=\"%s\"} %d\n", h, db[h], count[h]
    printf "mysql_slow_query_total_time_seconds{sql_hash=\"%s\",db=\"%s\"} %.6f\n", h, db[h], time[h]
    printf "mysql_slow_query_max_query_time_seconds{sql_hash=\"%s\"} %.6f\n", h, max[h]
    printf "mysql_slow_query_top{sql_hash=\"%s\",db=\"%s\",sample=\"%s\"} 1\n", h, db[h], sample[h]
  }
}' "$TMP.tsv" >> "$TMP"
rm -f "$TMP.tsv"

echo "mysql_slow_query_up 1" >> "$TMP"
mv "$TMP" "$OUT"
chmod 644 "$OUT"
