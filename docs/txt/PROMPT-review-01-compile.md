执行 my-xhs 项目的全量编译验证，然后报告结果。
请严格按照以下步骤执行并输出报告：
1. cd /data/workspace/my-xhs && mvn clean compile -T 4
2. 如果 BUILD SUCCESS，输出"✅ 全量编译通过"
3. 如果有 ERROR，列出错误文件和行号
4. 如果有 WARNING，列出所有警告
5. 用 tokei 统计项目当前代码规模，输出文件数、代码行数、注释行数

**输出到文件**: /data/workspace/my-xhs/docs/txt/tmp-result/01-compile-result.md
