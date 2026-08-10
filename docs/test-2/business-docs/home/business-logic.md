# my-xhs-home 业务逻辑分析
## 一、Feed流排序 - 时间倒序ZSET score
## 二、大V判定 - Redis Set粉丝数>10k→标记outbox模式  
## 三、断点续推 - Redis hash记录cursor, 崩溃后MQ重投恢复
## 四、@Profile("dev")端点 - H06/H07仅开发环境, 直写Redis不经过MQ
