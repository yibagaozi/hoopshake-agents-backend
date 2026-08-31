# syntax=docker/dockerfile:1
# HOOPSHAKE 云端后端(cloud 模块)镜像。多模块:cloud 依赖 contracts,用 -pl cloud -am 构建

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src

# 先只拷 pom,利用镜像层缓存预取依赖
COPY pom.xml ./
COPY contracts/pom.xml contracts/pom.xml
COPY cloud/pom.xml cloud/pom.xml
COPY edge/pom.xml edge/pom.xml
RUN mvn -q -B -pl cloud -am dependency:go-offline || true

# 再拷源码构建;跳测试
COPY . .
RUN mvn -q -B -pl cloud -am -DskipTests clean package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:25-jre
WORKDIR /app
# 非 root 运行
RUN useradd -r -u 10001 appuser
COPY --from=build /src/cloud/target/cloud-*.jar /app/app.jar
USER appuser

EXPOSE 8080
# JAVA_OPTS 可在运行时注入(-Xmx 等);SPRING_PROFILES_ACTIVE 可选 prod
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
