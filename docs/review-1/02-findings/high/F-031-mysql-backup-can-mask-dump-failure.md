# F-031 MySQL 备份脚本可能在 mysqldump 失败时生成“成功”备份

## 严重度

High

## 涉及文件

- `config/deploy-cloud/mysql-backup.sh:8`
- `config/deploy-cloud/mysql-backup.sh:21-29`

## 现象

备份脚本使用：

```bash
mysqldump ... | gzip > "$DUMP"
```

脚本只有 `set -e`，没有 `set -o pipefail`。当 `mysqldump` 失败而 `gzip` 成功时，管道退出码通常来自 gzip，脚本继续输出“备份完成”，留下损坏或不完整的 `.sql.gz` 文件。

容器 fallback 分支同样使用管道，且把 mysqldump stderr 丢弃：`mysql-backup.sh:27-29`。

## 影响

1. 备份目录存在看似正常但不可恢复的备份文件。
2. 原始失败原因被隐藏，直到恢复演练或灾难发生才暴露。
3. 数据库连接、权限、磁盘空间、容器异常都可能导致静默失败。

## 修复建议

1. 使用 `set -Eeuo pipefail`。
2. 先输出临时文件，成功后原子 rename；失败时删除临时文件。
3. 校验 gzip 可解压、SQL 文件非空、关键数据库/表存在。
4. 定期做自动恢复演练，而不是只检查备份文件大小。
5. 避免把密码放在命令行参数中，改用受保护的 defaults-extra-file 或 secrets 注入。

## 是否需要补充验证

需要模拟 mysqldump 连接失败，确认脚本当前是否仍退出码为 0、是否保留“成功”备份文件。