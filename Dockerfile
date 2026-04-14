# 基础镜像
FROM eclipse-temurin:21-jdk-alpine

# docker 内部的指定工作目录
WORKDIR /app

COPY ./codesandbox-0.0.1-SNAPSHOT.jar .
VOLUME ./.mysql-data:/var/lib/mysql

# 暴露端口
EXPOSE 50001


# 启动命令
ENTRYPOINT ["java","-jar","/app/codesandbox-0.0.1-SNAPSHOT.jar"]
