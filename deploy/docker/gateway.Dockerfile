# MiQroKey Inference Gateway image (#479). Build context is the repository root:
#   docker build -f deploy/docker/gateway.Dockerfile .
#
# 不声明 `# syntax=docker/dockerfile:1`：受限网络下拉不到 docker/dockerfile 前端镜像
# （实测 auth.docker.io 超时）；`RUN --mount` 在引擎内置前端（Docker 25+ / Desktop）即可用。
# 构建用镜像自带 Maven 3.9.9（与 .mvn/wrapper/maven-wrapper.properties 同版本），不经 mvnw：
# wrapper 在容器内自举发行包会引入额外下载，且并行构建共享 m2 缓存时会撞安装竞态（实测失败）。
FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /src
COPY backend/ backend/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml -pl gateway-app -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
RUN addgroup -g 10001 -S miqrokey && adduser -u 10001 -S -G miqrokey -h /app miqrokey
WORKDIR /app
COPY --from=build --chown=10001:10001 /src/backend/gateway-app/target/gateway-app-*-exec.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
