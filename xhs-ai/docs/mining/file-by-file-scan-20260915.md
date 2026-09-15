# xhs-ai 逐文件扫描（2026-09-15）

## 覆盖面
- **69 个 Java 类，0 个无注释文件**；16 个包：agent/tools 21、approval 7、api 7、session 5、config 5、web 3、security 3、model 3、knowledge 3、agent 3、eval 2、audit 2、mq 1、log 1、code 1、common 1
- 测试：20 个测试类 / 61 个 @Test
- resources：application.yml、V1/V2/V3 迁移、logback-spring.xml（知识卡在 knowledge/ 单列）

## 唯一新挖点：EsLogSearchService（DLQ 首错日志关联）
`firstFailure(originMsgId, msgId, keys...)`：
- 候选词去空后**按顺序逐个检索**（originMsgId → msgId → keys），命中即返回
- 匹配字段覆盖：`message` / `MSG_ID` / `keys` / `key` / `UNIQ_KEY`（都用 match_phrase）
- 返回最早一条相关日志 + `matchedBy` 标注命中的候选词；全部未命中时返回 `searched` 列表并提示"日志可能未携带消息 ID，可用消息体+时间窗人工缩小"
- 价值：DLQ 诊断时把"死信消息"与"服务端首条失败日志"自动关联，是"证据链"里的关键一跳

## 完整性与结论
- 全部核心类（16 工具 / 7 审批 / 7 接口 / 5 会话 / 5 配置 / 3 模型 / 3 知识 / 3 安全 / 2 评测 / 2 审计 / DLQ 服务 / 代码定位 / TraceId / 幂等）均已在既有素材中覆盖
- 无隐藏未挖类；resources 无额外配置面
