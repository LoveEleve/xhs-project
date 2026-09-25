# D5.7 证据包 · 事件哈希链（防篡改）

> 生成: 2026-09-25 ｜ 变更: chg-d1f40080 ｜ 工具: harness-runner CLI
> 来源：`专题-深度自拷打.md` 弱点清单 P1-5 的修复落地

## 1. 修了什么

事件流 `events.jsonl` 由纯 append-only 升级为 **SHA-256 链式哈希**：
- 每条事件含 `prevHash` + `hash`（`hash = SHA256(changeId|type|stage|message|occurredAt|prevHash)`），链首锚定 `GENESIS`
- 新增 `verify --change <id>` 命令：逐条重算并校验前向链接

## 2. 演示实录

**链完整（8 条事件）：**
```
$ java -jar harness-runner-app.jar verify --change chg-d1f40080 --workspace evidence/d5-hashchain
变更: chg-d1f40080
事件链: 完整
  链完整（已校验 8 条，跳过历史未链化 0 条）
exit=0
```

**篡改一条事件（`attempt=1` → `attempt=9`）：**
```
$ sed -i 's/attempt=1/attempt=9/' .../events.jsonl
$ java -jar harness-runner-app.jar verify --change chg-d1f40080 --workspace evidence/d5-hashchain
变更: chg-d1f40080
事件链: 断裂
  第 2 条事件哈希不匹配（疑似篡改）
exit=1
```

## 3. 诚实边界

- 防的是**局部篡改**（改内容/删中间行/断链会被发现）；**整链重写**无法自证（自己算自己存），需外锚：
  SCM 提交 / 对象存储 WORM / 时间戳服务（TSA）/ 签名。
- 历史证据包（d4-xhs / d5-p0）的事件未链化，`verify` 会统计为"跳过历史未链化 N 条"，不影响新链校验。
- `append` 现为 O(n) 读取定位前向哈希（事件量小，可接受；大日志可优化为尾行读取）。

## 4. 复现

```bash
JAR=harness-runner-app/target/harness-runner-app-0.1.0-SNAPSHOT.jar
WS=evidence/d5-hashchain
java -jar $JAR verify --change chg-d1f40080 --workspace $WS   # 完整
sed -i 's/attempt=1/attempt=9/' $WS/changes/chg-d1f40080/events.jsonl
java -jar $JAR verify --change chg-d1f40080 --workspace $WS   # 断裂，exit 1
```

## 5. 测试

- `EventHasherTest`（2）：哈希确定性 / 内容与前向哈希敏感
- `FileStoreTest`（+3）：链完整 / 篡改定位 / 历史未链化行跳过
- `HarnessCliTest`（+1）：verify 完整 exit 0；篡改后 exit 1 + "疑似篡改"
