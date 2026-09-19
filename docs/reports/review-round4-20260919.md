# Review 第四轮（2026-09-19）：日志敏感信息泄漏扫描 + Logstash 管道漂移修复

> 换面：敏感信息扫描（代码日志语句 / 运行时日志 / 采集管道），兼顾"文档描述的管道"与"实际运行的管道"一致性。

## 一、扫描结果

**静态（代码日志语句）**：命中项均为类型/标签而非值（`Token 类型错误: type=access`、`ticket 类型错误`、`uri=...`），无整请求体打印（user/payment 均无）✓。

**运行时（/data2/logs）**：
| 模式 | 结果 |
|---|---|
| JWT（`eyJhbGci`/`Bearer eyJ`） | 0 命中 ✓ |
| 密码串（`Xhs@2026`） | 0 命中 ✓ |
| `password=` | 30 文件命中，均为 **Nacos 注销失败**日志打印 `NacosRegistration` 对象——实测 `password=''` 为空，**非泄漏**（噪音项） |
| `ticket=` | 3 文件命中 → **真实泄漏**（见下） |

## 二、发现与修复

### ① 网关访问日志明文记录 SSE ticket（真泄漏，已修）
- 现象：`[Gateway] >>> ... query=ticket=55d0b3f2...` —— SSE 两步握手的**一次性凭据**明文落日志/落 ES。
- 修复：`RequestLogFilter.maskSensitiveQuery()`，对 `ticket/token/access_token/refresh_token/password/secret/signature/sign/authorization/code/captcha` 的 query 值统一替换为 `***`。
- 验证（网关 22:52 重发后）：`query=ticket=***` ✓（旧行仍为明文，新行已脱敏）。

### ② Logstash 运行时管道与仓库文件漂移，且无脱敏（已修）
- 现象：容器实际用 compose 内联 `-e` 管道（**无任何 filter**），而仓库 `config/logstash/logstash.conf`（题库 xhs/53 引用的 grok/date 版）从未被使用——**文档 vs 现实漂移**；且全链无脱敏。
- 修复：
  1. `logstash.conf` 新增 mutate gsub：对 `message` 中 `ticket=/token=/password=` 等凭据与 `password='...'` 形态脱敏；
  2. compose 改为**挂载仓库管道文件**（单一事实源），移除内联 `-e`；
  3. `--config.test_and_exit` 语法校验通过后重建容器（注意：`docker compose up` 会连带处理其他服务并因既有容器名冲突失败，需 `--no-deps`）。
- 验证：
  - 管道健康、15044/15045 监听、**索引持续增长**（386,758 → 387,072+）；
  - 最新文档 `service=my-xhs-payment`（grok 提取生效，此前内联管道无此 filter）；
  - 注入测试事件 `[测试] query=ticket=SECRET_TEST_123&page=1, password='abc123'` → ES 中为 `[测试] query=***&page=1, ***`，**明文不可检索** ✓。

### ③ 附带：E2E step29 断言窗口过紧（测试资产修复）
- 现象：退款通知为异步链路，脚本 +2s 断言偶发 29/30；复查 DB 通知已刷新（`aggregate_count=3`、内容"退款已到账"）、订单 status=5 → 属断言窗口问题。
- 修复：step29 改为轮询等待（最长 15s）→ 重跑 **30/30**。

## 三、观察项（登记，不修）
- Nacos 注销失败 ERROR 会 dump `NacosRegistration`（password 为空）；如后续出现敏感字段，可由 Logstash 脱敏兜底。
- `logstash.conf` 输出段仍为明文 ES 密码（部署配置固有，属 F-019 类别）。

## 四、验证与回归
| 项 | 结果 |
|---|---|
| 网关脱敏 | `ticket=***` ✓ |
| ES 脱敏 | 测试事件 `***`，明文 0 命中 ✓ |
| ELK 回流 | 索引文档数持续增长、service 字段正常 ✓ |
| 服务健康 | 15/15 ✓ |
| E2E | **30/30**（`e2e-business-chain-run-20260919-230030.json`） |
