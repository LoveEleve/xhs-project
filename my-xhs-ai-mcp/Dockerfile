# my-xhs-ai-mcp 独立 Dockerfile（M8-1）
# 构建：docker build -f my-xhs-ai-mcp/Dockerfile -t myxhs/ai-mcp:1.0.0 .

# ---- Stage 1: 编译 ----
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml ./
COPY my-xhs-ai-tools/pom.xml my-xhs-ai-tools/
COPY my-xhs-ai-mcp/pom.xml my-xhs-ai-mcp/

RUN mvn dependency:go-offline -pl my-xhs-ai-mcp -am -q -DskipTests

COPY . .
RUN mvn package -pl my-xhs-ai-mcp -am -q -DskipTests

# ---- Stage 2: 运行 ----
FROM eclipse-temurin:17-jre-alpine AS runtime

RUN apk add --no-cache curl tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime

COPY --from=builder /build/my-xhs-ai-mcp/target/my-xhs-ai-mcp-1.0-SNAPSHOT.jar app.jar

EXPOSE 19021
ENTRYPOINT ["java", "-jar", "/app.jar"]
