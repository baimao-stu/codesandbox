# codesandbox

### 介绍
代码沙箱，用于编译执行代码。

实现了ACM模式的支持Java和C++的本地沙箱、Args模式的支持Java的本地沙箱和Docker沙箱

项目可作为独立的服务提供接口给其他项目：http://localhost:8888/executeCode

### 技术栈
基于 SpringBoot 开发

利用 Process 类获取沙箱的输入和输出

Java 安全管理器做安全控制

docker-java 操作 Docker：https://github.com/docker-java/docker-java

