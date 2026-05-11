# CI/CD 与自动化部署

> 所属维度：DevOps | 开发阶段：Phase-6 | 核心工具：Jenkins + Docker + K8s

---

## 🎯 一、部署架构总览

```
代码提交 → Jenkins Pipeline → Maven Build → Docker Build → Push Registry → K8s Rolling Update
                                   ↓                                           ↓
                              单元测试                                    灰度发布(5%→100%)
```

---

## 🏗️ 二、Docker Compose 一键启动

### 2.1 基础设施清单

| 组件 | 版本 | 用途 | 部署方式 |
|------|------|------|----------|
| MySQL | 8.0 | 业务数据存储 | 1主1从 |
| Redis | 7.x | 缓存+锁+限流+计数 | Sentinel: 1主2从3哨兵 |
| Elasticsearch | 8.x | 搜索引擎 | 单节点 |
| RocketMQ | 5.x | 消息队列 | NameServer+Broker |
| Nacos | 3.x | 注册+配置中心 | 单节点 |
| SkyWalking | 9.x | 链路追踪 | OAP+UI |
| Prometheus | 2.x | 指标采集 | 单节点 |
| Grafana | 10.x | 可视化看板 | 单节点 |
| XXL-Job | 3.0 | 分布式调度 | Admin+Executor |
| Canal | 1.1.x | binlog监听 | 单节点 |

> 完整的 `docker-compose.yml` 配置见 `04-基础设施与部署.md §2`

---

## 💻 三、Dockerfile 模板

```dockerfile
FROM openjdk:17-jdk-slim
LABEL maintainer=my-xhs

# SkyWalking Agent
ADD skywalking-agent.jar /skywalking/agent/skywalking-agent.jar

WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV SW_AGENT_NAME="my-xhs-service"
ENV SW_AGENT_COLLECTOR_BACKEND_SERVICES="skywalking-oap:11800"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -javaagent:/skywalking/agent/skywalking-agent.jar -jar app.jar"]
```

---

## 🔧 四、Jenkins Pipeline

```groovy
pipeline {
    agent any
    environment {
        DOCKER_REGISTRY = 'registry.cn-hangzhou.aliyuncs.com'
        IMAGE_PREFIX = 'my-xhs-cloud'
        VERSION = "${env.BUILD_NUMBER}"
    }
    stages {
        stage('Checkout') { steps { checkout scm } }
        stage('Build')    { steps { sh 'mvn clean package -DskipTests' } }
        stage('Test')     { steps { sh 'mvn test' } }
        stage('Build Images') {
            steps {
                script {
                    def services = ['gateway','user','note','social','product',
                                    'cart','order','inventory','coupon','search',
                                    'notification','counter']
                    services.each { svc ->
                        sh "docker build -t ${DOCKER_REGISTRY}/${IMAGE_PREFIX}/${svc}:${VERSION} -f my-xhs-${svc}/Dockerfile my-xhs-${svc}/"
                    }
                }
            }
        }
        stage('Push Images') {
            steps {
                script {
                    sh "docker login ${DOCKER_REGISTRY}"
                    // ... push each image
                }
            }
        }
        stage('Deploy') {
            steps {
                sh "kubectl set image deployment/my-xhs-order my-xhs-order=${DOCKER_REGISTRY}/${IMAGE_PREFIX}/order:${VERSION}"
                sh "kubectl rollout status deployment/my-xhs-order"
            }
        }
    }
    post {
        failure { echo 'Build failed!' }
        success { echo 'Build succeeded!' }
    }
}
```

---

## ☸️ 五、K8s 部署

### 5.1 Deployment 关键配置

| 配置项 | 值 | 说明 |
|--------|-----|------|
| replicas | 2 | 最少2个实例保高可用 |
| strategy | RollingUpdate(maxSurge=1, maxUnavailable=0) | 滚动更新不丢流量 |
| resources.requests | 512Mi / 250m | 资源预留 |
| resources.limits | 1Gi / 500m | 资源上限 |
| readinessProbe | /actuator/health/readiness | 就绪检测(30s初始延迟) |
| livenessProbe | /actuator/health/liveness | 存活检测(60s初始延迟) |
| terminationGracePeriodSeconds | 45 | 优雅停机时间 |
| preStop | curl nacos-deregister + sleep 15 | 先注销Nacos再停 |

### 5.2 灰度发布

```
灰度策略：
1. 新版本Deployment(replicas=1) + Nacos元数据标记version=v2
2. Gateway路由规则：Header x-canary:true → 路由到v2
3. 内部测试通过后 → 逐步扩大v2实例数
4. 全量切换后 → 删除v1 Deployment
```

---

## ⚖️ 六、方案对比

| 维度 | Jenkins | GitLab CI | GitHub Actions |
|------|---------|-----------|----------------|
| 私有部署 | ✅ | ✅ | ❌ (SaaS) |
| Pipeline即代码 | Jenkinsfile(Groovy) | .gitlab-ci.yml | .github/workflows |
| K8s集成 | 插件丰富 | 内置Runner | 需自建Runner |
| 学习成本 | 中 | 低 | 低 |

**最终选择**：Jenkins — 私有部署、插件生态丰富、企业标配。

---

## 🎤 七、面试考察点

### Q1: 你们的CI/CD流程是怎样的？

**推荐回答思路**：

> 1. "代码提交触发Jenkins Pipeline → Maven Build → 单元测试 → Docker Build → Push镜像 → K8s滚动更新"
> 2. "K8s RollingUpdate + Nacos注册中心感知，maxUnavailable=0保证更新期间不丢流量"
> 3. "灰度发布：新版本先1个Pod+Gateway灰度路由，验证通过再全量"

### Q2: 滚动更新怎么保证不丢请求？

**推荐回答思路**：

> 1. "K8s preStop钩子先从Nacos注销，等15秒让流量切走"
> 2. "Spring Boot graceful shutdown等待进行中的请求完成(最多30秒)"
> 3. "maxUnavailable=0保证旧Pod没停完新Pod已经Ready"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《分布式系统应用设计》(Brendan Burns) | 第3章 | K8s部署模式+优雅停机 |
| 📄 04-基础设施与部署.md | §2-§5 | Docker/K8s/Jenkins完整配置 |
