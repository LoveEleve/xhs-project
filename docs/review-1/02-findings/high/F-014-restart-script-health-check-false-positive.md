# F-014 重启脚本健康检查假阳性，端口被旧实例占用时误报服务已就绪

## 严重度

High

## 涉及文件

- `scripts/restart-service.sh:43-44`
- `scripts/restart-service.sh:53-58`
- `scripts/restart-service.sh:61-64`

## 现象

`restart-service.sh` 用 `curl http://localhost:$PORT/actuator/health` 判断服务是否就绪。当旧进程未被完全杀掉、仍占用端口时，curl 会命中旧进程的 health 端点返回 200，脚本误判"新进程已就绪"，而新进程实际因端口冲突启动失败。

## 证据

1. 杀旧进程只取第一个匹配：`ps aux | grep "[j]ava.*my-xhs-$MODULE-1.0" | head -1`，`restart-service.sh:43`。若存在多个同模块进程，只杀一个。
2. 就绪判断只 curl 端口 health：`restart-service.sh:53-58`，不校验响应来自新 PID。
3. 写 pids 时取第一个匹配 java 进程：`restart-service.sh:61-63`，可能写入旧进程 PID。
4. 实测（2026-08-17）：gateway 重启时旧实例 3928417 占用 19000，新进程 999497 因端口冲突 `APPLICATION FAILED TO START`，但脚本 curl 命中旧实例 health 返回 200，误报 `✅ gateway UP (pid=999497)`。

## 触发条件

1. 存在多个同模块 java 进程（僵尸进程、历史遗留、并发重启）。
2. 旧进程未在 `sleep 4` 内释放端口。
3. 新进程启动失败但端口仍被旧实例占用。

## 影响

1. 脚本误报服务已就绪，实际新代码未生效。
2. 运维/测试基于错误状态继续操作，可能验证的是旧代码。
3. pids 文件写入错误 PID，后续 stop/restart 定位错误进程。
4. 新进程失败原因（端口冲突）被掩盖，排查困难。

## 修复建议

1. 杀旧进程时按模块名全量清理（`pkill -f` 或循环 kill 所有匹配 PID），而非 `head -1`。
2. 就绪判断应校验响应来自新 PID：先记录新进程 PID，再确认该 PID 存活且端口由它监听（`ss -lntp` 校验 PID）。
3. 启动后检查新进程日志是否出现 `APPLICATION FAILED TO START` / `Port already in use`，出现则判定失败。
4. 写 pids 前确认端口监听者 PID 与新进程 PID 一致。

## 残余风险

即使修复脚本，仍需在部署后做功能探活（而非仅 health 端点），因为 health 只反映进程存活，不反映业务链路可用。

## 是否需要补充验证

需要构造"旧进程占用端口 + 新进程启动失败"场景，确认脚本能正确判定失败而不是误报 UP。