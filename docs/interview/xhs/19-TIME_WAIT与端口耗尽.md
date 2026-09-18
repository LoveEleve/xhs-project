# 第19题 | TIME_WAIT 与临时端口耗尽

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：2MSL、四次挥手、四元组、ephemeral port、连接复用、keep-alive、tcp_tw_reuse

## 问题
什么是 TIME_WAIT？为什么会有大量 TIME_WAIT？什么情况会把临时端口打满？怎么排查和治理？

## 面试可讲版（五段式）

**① 业界背景**
TIME_WAIT 是 TCP 主动关闭方的正常状态：等待 **2MSL**（Linux 约 60s），目的有两个——保证最后一个 ACK 能重传（对端没收到 FIN 重传时我还能响应）、让本次连接的四元组在网络中彻底消散（防止旧连接的延迟包被新连接误收）。**TIME_WAIT 本身不是 bug**，问题出在"主动关闭太多 + 端口不够"：客户端连服务端时，源端口是从 `ip_local_port_range`（默认约 32768–60999，**约 2.8 万个**）里选的，短时间海量短连接会把可用端口耗尽，表现为 `Cannot assign requested address`。

**② 项目选择**
- 本项目**没有 TIME_WAIT 事故**（诚实说明），但连接治理是做了的，可以讲配置口径：
  - **HTTP 连接复用**：Tomcat `keep-alive-timeout: 60000` + `max-keep-alive-requests: 200`——单连接复用 200 次再关，长连接为主；
  - **连接池**：Redis Lettuce `max-active:15 / max-idle:8 / min-idle:4 / max-wait:3000ms`；Hikari（DB）固定池；MQ 长连接；
  - **服务间调用**：Feign 走 HTTP，全部经服务名+网关/直连，连接由客户端连接池复用；
  - 调用方（客户端）与服务端都有各自的连接策略——**谁先关谁是 TIME_WAIT 方**，长连接下主动关闭次数骤降。

**③ 坑**
- **"TIME_WAIT 多=有问题"是误区**：先看 `ss -s`/`netstat` 的分布：TIME_WAIT 集中在**客户端侧**是短连接打爆端口的前兆；集中在服务端且端口未耗尽，多数是正常；
- **改 `tcp_tw_recycle` 的时代坑**：NAT 环境下会丢包（Linux 4.12 已移除），别答这个；
- **`SO_REUSEADDR` 只解决"绑定"不解决"占用"**：它让服务端重启能 bind，但不释放 TIME_WAIT；
- **连接泄漏**：池化连接没还回池（异常路径没 close）会伪装成"连接不够"，实际是泄漏——看池指标（active/idle/pending）比看 TIME_WAIT 更直接。

**④ 兜底**
- 临时端口排查四板斧：`ss -s`（总览）→ `ss -tan state time-wait | wc -l`（数量）→ `ss -tan state time-wait dst :端口`（对端集中）→ `/proc/sys/net/ipv4/ip_local_port_range`（可用量）；
- 治理顺序：**连接复用（keep-alive/池） > 缩短 MSL（tcp_fin_timeout，谨慎） > tcp_tw_reuse（仅出向、需时间戳）**；
- 本项目口径：以 keep-alive + 连接池为主，未依赖内核参数硬调。

**⑤ 话术**
> "TIME_WAIT 是主动关闭方的正常状态，等 2MSL 保证最后 ACK 与旧包消散，不是越大越坏。真正危险的是客户端侧海量短连接把 2.8 万临时端口打满，报 Cannot assign requested address。治理优先级是连接复用——我们的 Tomcat keep-alive 60s/200 次、Redis/DB/MQ 都走池化长连接，从源头减少主动关闭；内核参数（fin_timeout/tw_reuse）是最后手段，tw_recycle 已废弃不用。"

## 追问与参考回答
**追问1：只有主动关闭方进 TIME_WAIT 吗？** 是（谁先发 FIN 谁是主动关闭方）；"同时关闭"双方都会进。
**追问2：CLOSE_WAIT 多说明什么？** 被动关闭方收到 FIN 却没调 close——代码漏关连接/资源，属于应用 bug，和 TIME_WAIT 是两码事。
**追问3：tcp_tw_reuse 能随便开吗？** 它只对**出向连接**生效且依赖时间戳，NAT 场景要谨慎；服务端监听的 TIME_WAIT 用 SO_REUSEADDR 处理。
**追问4：长连接会不会把服务端连接数打满？** 会——所以服务端要限制 max-connections/max-keep-alive-requests（本项目 maxConn=8192），并用 max-keep-alive-requests 让连接周期性轮换。
**追问5：怎么证明是端口耗尽而不是 CPU/带宽瓶颈？** 看报错（Cannot assign requested address）、客户端 TIME_WAIT 计数、port_range 消耗、以及客户端并发与 QPS×平均连接时长的乘积估算。

## 发散追问地图（横向）
- TCP 状态机：CLOSE_WAIT/TIME_WAIT/FIN_WAIT2、半连接队列、SYN flood。
- 连接治理：HTTP/2 多路复用、连接池参数（Hikari/Lettuce/OkHttp）、池化 vs 长连接。
- 内核参数：ip_local_port_range、tcp_max_tw_buckets、tcp_fin_timeout、somaxconn。
- 观测：ss/netstat、node_exporter 的 TIME_WAIT 指标、连接池 metrics（hikaricp_connections_pending）。
- 容器网络：NAT/conntrack 表上限、容器源端口复用。

## 面试官评分点
**高级开发级**：能讲清 2MSL 两个目的；能区分 TIME_WAIT/CLOSE_WAIT。
**架构师加分**：端口耗尽的量化（2.8 万 + 四元组）；治理优先级（复用>调参）；池指标优先于 TIME_WAIT 观测；主动关闭方视角。
**危险信号**：说 TIME_WAIT 是 bug；推荐 tcp_tw_recycle；只答"调内核参数"。

## 本项目真实证据
- Tomcat：`server.tomcat.keep-alive-timeout: 60000`、`max-keep-alive-requests: 200`（服务 application.yml）；`MyXhsTomcatCustomizer` 定制 maxThreads=150/minSpare=15/maxConn=8192；
- Redis：Lettuce `max-active:15/max-idle:8/min-idle:4/max-wait:3000ms`（application.yml）；
- Grafana node 面板含 TIME_WAIT 指标（无项目事故记录，本题按原理+配置写）。

## 版本与来源
TCP RFC 793/1122（TIME_WAIT/2MSL）；Linux ip-sysctl 文档；本项目连接配置与 node_exporter 指标。

## 真实性说明
原理为标准知识；本项目**无 TIME_WAIT 事故**，配置口径为代码事实；未编造故障案例。
