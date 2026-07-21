对 my-xhs 项目执行全量代码审计，输出以下内容：
1. 运行 sverklo audit，提取健康度评级、孤儿符号数量、高耦合文件列表
2. 用 codegraph status 展示索引统计
3. 用 tokei 统计 Java 代码行数和模块分布
4. 用 rg 搜索全项目中是否还有残留的旧 Key 格式（inventory:total:、myxhs:cart:items:、coupon:stock:），如果有输出文件和行号
5. 用 codegraph files --format grouped 展示模块文件分布
