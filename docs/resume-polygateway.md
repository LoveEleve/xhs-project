<!-- PolyGateway 单项目紧凑简历（全系列版见 resume-poly.md）。
     口径：全部条目对应 poly 仓库实际实现；不写测试计数与内部开发术语。
     代码见 https://github.com/LoveEleve/poly-project/tree/main/polygateway -->
# 【姓名】

电话：【】｜邮箱：【】｜城市：【】｜工作年限：【】
求职意向：Java 后端开发（网关 / 流量治理 / 分布式方向）

## 教育背景
【学校 / 专业 / 学历 / 起止时间】

## 工作经历
【公司 / 职位 / 起止时间】　【一句话职责】

## 专业技能
- 语言与框架：Java 17、Spring Boot 3.2.5、Spring Cloud 2023.0.3、Spring Cloud Gateway（WebFlux）
- 基础设施：服务注册与发现、Spring Cloud LoadBalancer、Spring MVC / WebFlux 双栈
- 专项：端点级路由与路由自动生成、Content-Type / Accept 精确匹配、路由级限流与重试治理
- 工程化：Maven 可选依赖治理、Spring Boot Starter 条件装配、端到端验证

---

端点级网关路由项目（PolyGateway · Spring Cloud Gateway WebFlux · github.com/LoveEleve/poly-project）
● 端点自动发现：业务服务启动时自动采集对外 HTTP 接口（路径 / 方法 / 参数 / 请求头 / Content-Type / Accept）并写入注册中心元数据，服务零接入成本，自动排除管理类端点，跨实例采集结果一致
● 端点级路由：网关根据元数据自动生成接口级精确路由，复用 Spring Cloud Gateway 官方谓词与响应式负载均衡，网关线程零阻塞、路由可视化；支持按服务 / 路径 / 方法隐藏内部接口（不生成路由，不可达即 404），并按规则为接口挂载限流与重试
● 实时生效：注册中心心跳即刷新路由，服务发布新接口后无需重启网关即可访问
● 依赖精简：网关依赖可选化引入，仅提供采集端的服务无需引入网关栈，与服务解耦
