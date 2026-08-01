# SkyWalking HTTP 链路缺失 — 问题说明（给中间件团队）

> 验证日期：2026-07-31

---

## 现象

SkyWalking UI 中只有 `Redisson/SENTINEL` 和 `/actuator/prometheus` 等零散 trace，**业务 HTTP 请求（Gateway→User→Home 等）完全没有链路数据**。

## 根因

SkyWalking agent 版本 **9.1.0** 缺少 Spring MVC 6.x（jakarta.servlet）插件：

```
skywalking-agent/plugins/ 中只有：
  apm-springmvc-annotation-3.x-plugin-9.1.0.jar  （javax）
  apm-springmvc-annotation-4.x-plugin-9.1.0.jar  （javax）
  apm-springmvc-annotation-5.x-plugin-9.1.0.jar  （javax）
  无 6.x 插件

项目使用 Spring Boot 3.2.5（Spring MVC 6 / jakarta.servlet.http.HttpServletResponse）
agent 日志确认：
  WARN: enhance class org.apache.catalina.core.ApplicationDispatcher by plugin
  apm.tomcat78x... is not activated. Witness class javax.servlet.http.HttpServletResponse does not exist.
```

Redisson 插件的 trace 能采到（不依赖 servlet），所以 UI 有零散数据，给人"agent 工作正常"的错觉。

## 需要中间件团队做的

1. **升级 skywalking-agent 到 9.2.0+**（支持 Spring MVC 6.x / jakarta）
   - 或确认当前 9.1.0 是否有 springmvc-annotation-6.x-plugin 可单独补充
2. **升级后重启全部微服务**（agent 随 JVM 启动加载）
3. **验证**：
   ```
   通过 Gateway 发一个请求 → 5 分钟内 SkyWalking UI 出现
   GET:/api/user/10002/info 的完整链路（Gateway → User）
   ```

## 补充

- 15 个微服务已确认全部挂载 agent（本地重启验证过）
- SkyWalking OAP: 21.130.247.89:8080
- agent 路径: /data/workspace/my-xhs/skywalking-agent/
- 其他可观测性组件状态：
  - Prometheus（19090）✅ 14/16 目标 UP
  - Logstash→ES ✅ traceId 字段已修复
  - Kibana ❌ 未部署（5601 关闭），日志查询只能走 ES API
