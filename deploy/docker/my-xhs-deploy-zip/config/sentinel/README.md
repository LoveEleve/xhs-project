# Sentinel 规则管理

## 规则持久化
Sentinel 规则通过 Nacos 数据源持久化存储，服务重启后规则不丢失。

## 规则类型
- **flow-rules**: 流量控制规则（QPS/并发线程数）
- **degrade-rules**: 熔断降级规则（慢调用比例/异常比例）
- **param-flow-rules**: 热点参数限流规则

## 规则配置方式

### 方式一：Nacos 控制台
在 Nacos 控制台中创建配置：
- Data ID: `{服务名}-flow-rules`
- Group: `SENTINEL_GROUP`
- 配置格式: JSON

### 方式二：导入示例规则
将 `config/sentinel/` 下的 JSON 文件内容复制到 Nacos 控制台对应 Data ID。

## 示例：为 Gateway 添加流控规则

```json
[
  {
    "resource": "user-service",
    "limitApp": "default",
    "grade": 1,
    "count": 100,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
```

参数说明：
- `resource`: 资源名（Gateway 中为路由 ID 或服务名）
- `grade`: 0=线程数限流, 1=QPS 限流
- `count`: 阈值
- `strategy`: 0=直接, 1=关联, 2=链路
- `controlBehavior`: 0=快速失败, 1=Warm Up, 2=排队等待
