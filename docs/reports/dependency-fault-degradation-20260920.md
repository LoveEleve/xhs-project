# 依赖故障传播与降级矩阵（2026-09-20）+ chaosblade 可用性修复

> 目标：验证"下游变慢/不可用"时聚合层是否按超时预算降级、语义是否可区分；并回答 chaosblade 为何不可用。

## 一、chaosblade 为什么不可用（已修复）

| 项 | 结论 |
|---|---|
| 现象 | `/opt/chaosblade/chaosblade-1.7.4/blade` 不存在，`chaos-drill.sh` 依赖它 |
| 根因 | `/opt/chaosblade.tar.gz` 实为 **385 字节的阿里云 OSS AccessDenied XML**（下载桶 ACL 拒绝，错误页被当成压缩包保存），从未解压；GitHub releases 在本机不可达 |
| 修复 | 用官方 OSS 正确路径 `.../agent/github/1.7.4/chaosblade-1.7.4-linux-amd64.tar.gz`（57MB）安装到 `/opt/chaosblade/chaosblade-1.7.4/`（与脚本路径一致），`blade version` 正常 |
| 冒烟 | OS 级实验可用：`blade create cpu load --cpu-percent 5 --timeout 3` → code=200 |
| **局限** | JVM 实验在本环境**不生效**：`prepare jvm --pid` 成功、enhancer 注册成功，但对 `HomeController.getProductDetail`/`ProductController.getSpuDetail` 注入 delay 后请求耗时无变化（JDK17 + Spring Boot fat-jar 的 sandbox 兼容问题）→ 依赖注入改用 **tc netem**（本环境已验证手段） |
| 防复发 | `chaos-drill.sh` 增加 blade 预检与官方下载/局限说明 |

## 二、依赖故障注入实测（tc netem：product 19006 双向 +4s）

| 观测 | 结果 |
|---|---|
| 直连 product `/api/product/spu/1` | 200 / **16.0s**（多跳延迟叠加，严重变慢） |
| home 聚合 `/api/home/product/1`（修复前） | **3.007s 返回 404「商品不存在」** —— 依赖超时被伪装成"资源不存在" |
| home 聚合（修复后） | **3.007s 返回 503「商品服务繁忙，请稍后重试」** ✓ |
| 真实不存在的商品（无注入） | 404「商品不存在」保持不变 ✓ |
| 清除注入后 | 200 / 52ms，恢复正常 ✓ |

### 根因与修复（真问题）
- **根因 1（吞异常）**：`ProductAggService` 内层 `catch (Exception) → return emptyMap()`，上层判空 → `null` → HomeController 返回 404；使 `isCompletedExceptionally()` 降级分支成为死代码。
- **根因 2（定时器竞态）**：聚合 `allOf.get(3s)` 与 Feign 读超时(3s) 同为 3s，聚合超时先触发时 `spuFuture` 尚未完成 → 空数据 → 404。
- **修复**：① 依赖异常统一转 `DownstreamUnavailableException`（503）；② 聚合超时且 SPU future 未完成时同样按依赖降级。文件：`my-xhs-home/.../ProductAggService.java`。

## 三、环境扰动与恢复（过程记录）
- 环境于 10:23 发生重启，把 15 个服务从 `target/` 拉起（**未带 release 脚本的 `-Dspring.profiles.active=dev` 与环境变量**）→ home/notification 的 dev 测试端点 404（test-11 10/12）。
- 处置：全量 `release-service.sh` 重发 15 服务 → test-11 **12/12**、test-13 **9/9**、健康 **15/15**；清理 14 个残留 target 进程（仅匹配 `comm==java`，避免误杀调用方 shell）。

## 四、同类模式登记（待决策）
`CartAggService` / `NoteAggService` / `UserProfileAggService` 同样存在"吞异常→空集合"降级：
- 列表类（购物车/笔记/关注列表）降级为空列表，属可接受的产品决策；
- 单资源类（本次 product 详情）伪装成"不存在"具有误导性，已修复；其余如需区分可沿用 `DownstreamUnavailableException` 模式。

## 五、结论与口径
- **超时预算生效**：聚合层严格在 3s 返回，不随下游变慢而挂起；
- **降级语义可区分**（修复后）：资源不存在(404) vs 依赖不可用(503)；
- **故障注入工具链**：chaosblade OS 级可用、JVM 级受限；依赖级延迟用 tc netem 更可靠。
