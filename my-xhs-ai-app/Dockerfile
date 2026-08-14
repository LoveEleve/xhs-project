# my-xhs-ai-app 独立 Dockerfile（M8-1）
# 不依赖后端通用模板（其 COPY 列表缺 my-xhs-ai-tools，-am 构建会失败）。
# 构建：docker build -f my-xhs-ai-app/Dockerfile -t myxhs/ai-app:1.0.0 .
# 构建依赖链：my-xhs-ai-tools（app/mcp 共享的指标/观测工具）→ 本模块

# ---- Stage 1: 编译（根 pom + tools + 本模块）----
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先复制 pom（层缓存：依赖下载只依赖 pom，源码变化不触发）
COPY pom.xml ./
COPY my-xhs-ai-tools/pom.xml my-xhs-ai-tools/
COPY my-xhs-ai-app/pom.xml my-xhs-ai-app/

# 下载依赖（含 tools 模块依赖树）
RUN mvn dependency:go-offline -pl my-xhs-ai-app -am -q -DskipTests

# 全量源码并构建
COPY . .
RUN mvn package -pl my-xhs-ai-app -am -q -DskipTests

# ---- Stage 2: 运行 ----
FROM eclipse-temurin:17-jre-alpine AS runtime

RUN apk add --no-cache curl tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

COPY --from=builder /build/my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar app.jar

EXPOSE 19020
ENTRYPOINT ["java", "-jar", "/app.jar"]
