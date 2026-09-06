# content 覆盖对账与测试阻断

## 1. 覆盖状态定义

- `已读取+有行号证据`：已静态阅读并在分析中给出源码证据
- `测试不可执行`：测试源码已读取，但当前构建或运行存在阻断
- `需运行确认`：静态代码无法确认真实中间件、schema、消费者或部署行为
- `暂不分析`：明确不属于本模块范围

## 2. 38 个候选文件对账

### 顶层文件 2/2

1. `Dockerfile`：已读取+有行号证据，`Dockerfile:1-3`
2. `pom.xml`：已读取+有行号证据，`pom.xml:12-120`

### 主源码 30/30

1. `src/main/java/com/myxhs/content/ContentApplication.java`：已读取+有行号证据，`ContentApplication.java:15-21`
2. `src/main/java/com/myxhs/content/config/FileUploadConfig.java`：已读取+有行号证据，`FileUploadConfig.java:18-26`
3. `src/main/java/com/myxhs/content/config/RedisPubSubConfig.java`：已读取+有行号证据，`RedisPubSubConfig.java:18-23`
4. `src/main/java/com/myxhs/content/controller/CommentController.java`：已读取+有行号证据，`CommentController.java:41-141`
5. `src/main/java/com/myxhs/content/controller/NoteController.java`：已读取+有行号证据，`NoteController.java:41-171`
6. `src/main/java/com/myxhs/content/dto/request/CommentCreateRequest.java`：已读取+有行号证据，`CommentCreateRequest.java:16-38`
7. `src/main/java/com/myxhs/content/dto/request/NotePublishRequest.java`：已读取+有行号证据，`NotePublishRequest.java:17-47`
8. `src/main/java/com/myxhs/content/dto/request/NoteUpdateRequest.java`：已读取+有行号证据，`NoteUpdateRequest.java:14-37`
9. `src/main/java/com/myxhs/content/dto/response/CommentVO.java`：已读取+有行号证据，`CommentVO.java:18-48`
10. `src/main/java/com/myxhs/content/dto/response/NoteDetailVO.java`：已读取+有行号证据，`NoteDetailVO.java:12-54`
11. `src/main/java/com/myxhs/content/dto/response/NoteItemVO.java`：已读取+有行号证据，`NoteItemVO.java:12-36`
12. `src/main/java/com/myxhs/content/entity/Comment.java`：已读取+有行号证据，`Comment.java:18-37`
13. `src/main/java/com/myxhs/content/entity/LocalMessage.java`：已读取+有行号证据，`LocalMessage.java:18-48`
14. `src/main/java/com/myxhs/content/entity/Note.java`：已读取+有行号证据，`Note.java:17-55`
15. `src/main/java/com/myxhs/content/entity/NoteEvent.java`：已读取+有行号证据，`NoteEvent.java:19-46`
16. `src/main/java/com/myxhs/content/enums/AuditStatus.java`：已读取+有行号证据，`AuditStatus.java:11-26`
17. `src/main/java/com/myxhs/content/enums/NoteStatus.java`：已读取+有行号证据，`NoteStatus.java:18-60`
18. `src/main/java/com/myxhs/content/enums/NoteType.java`：已读取+有行号证据，`NoteType.java:11-25`
19. `src/main/java/com/myxhs/content/feign/InternalCallFeignConfig.java`：已读取+有行号证据，`InternalCallFeignConfig.java:12-17`
20. `src/main/java/com/myxhs/content/feign/UserFeignClient.java`：已读取+有行号证据，`UserFeignClient.java:15-22`
21. `src/main/java/com/myxhs/content/filter/DFAFilter.java`：已读取+有行号证据，`DFAFilter.java:32-306`
22. `src/main/java/com/myxhs/content/job/FeedMessageRetryJob.java`：已读取+有行号证据，`FeedMessageRetryJob.java:36-206`
23. `src/main/java/com/myxhs/content/mapper/CommentMapper.java`：已读取+有行号证据，`CommentMapper.java:15-57`
24. `src/main/java/com/myxhs/content/mapper/LocalMessageMapper.java`：已读取+有行号证据，`LocalMessageMapper.java:12-87`
25. `src/main/java/com/myxhs/content/mapper/NoteEventMapper.java`：已读取+有行号证据，`NoteEventMapper.java:10-11`
26. `src/main/java/com/myxhs/content/mapper/NoteMapper.java`：已读取+有行号证据，`NoteMapper.java:10-11`
27. `src/main/java/com/myxhs/content/service/CommentService.java`：已读取+有行号证据，`CommentService.java:49-501`
28. `src/main/java/com/myxhs/content/service/FileStorageService.java`：已读取+有行号证据，`FileStorageService.java:11-20`
29. `src/main/java/com/myxhs/content/service/LocalFileStorageService.java`：已读取+有行号证据，`LocalFileStorageService.java:29-120`
30. `src/main/java/com/myxhs/content/service/NoteService.java`：已读取+有行号证据，`NoteService.java:58-683`

### 资源文件 4/4

1. `src/main/resources/application.yml`：已读取+有行号证据，`application.yml:1-175`
2. `src/main/resources/application-datasource.properties`：已读取+有行号证据，`application-datasource.properties:1-10`
3. `src/main/resources/logback-spring.xml`：已读取+有行号证据，`logback-spring.xml:1-123`
4. `src/main/resources/sensitive-words.txt`：已读取+有行号证据，`sensitive-words.txt:1-12`

### 测试文件 2/2

1. `src/test/java/com/myxhs/content/service/NoteServiceTest.java`：已修复构造器依赖并执行通过，`NoteServiceTest.java:88-91`、`NoteService.java:60-69`
2. `src/test/java/com/myxhs/content/service/CommentServiceTest.java`：已修复构造器依赖并执行通过，`CommentServiceTest.java:75-77`、`CommentService.java:51-57`

## 3. 当前测试结论

已执行 `mvn -pl my-xhs-content -am test`，common 53 个测试、content 9 个测试全部通过。此前两个测试构造器缺失依赖的问题已修复；测试仍需继续补充 Mapper 精确调用、Feed/MQ、DFA 动态词库和文件上传边界。

## 4. 非测试运行确认项

- `t_note_event` 是否在真实数据库存在
- 根 V1/V2 与部署包初始化脚本实际采用哪套
- MyBatis-Plus 逻辑删除是否生效
- Feed Consumer 的幂等与状态更新语义
- Canal/ES 更新和删除链路
- User Feign 超时和通知降级
- 上传目录是否共享、文件访问是否需要签名
- 运行态指标、日志脱敏和 MQ 重试/DLQ

## 5. 结论

- 文件盘点：38/38
- 静态读取：38/38
- 测试可执行：2/2；content 9 个测试全部通过
- 真实中间件验证：未完成
- content 服务已重新打包并重启，19002 health 为 `UP`
- 整体 98%：不能宣称；当前为高覆盖静态分析，测试主路径已通过，但关键运行态链路和边界仍未验证。
