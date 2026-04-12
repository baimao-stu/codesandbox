package com.baimao.codesandbox.core.docker;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;

import java.io.File;

/**
 * @author baimao
 * @title JavaNativeCodeSandbox
 */
//@Component
public class JavaDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static Boolean FIRST_PULL_IMAGE = true;      //是否是初次拉取镜像

    private static final Long TIME_OUT = 10000L; //容器超时时间

    private static final String PREFIX = File.separator + "java";

    private static final String JAVA_NAME = "Main.java";

    private static final String DOCKER_IMAGE = "eclipse-temurin:21-jdk-alpine";

    public JavaDockerCodeSandbox(){
        super.prefix = PREFIX;
        super.main_name = JAVA_NAME;
        super.dockerImage = DOCKER_IMAGE;
        super.timeOut = TIME_OUT;
    }

    @Override
    protected ExecCreateCmdResponse createCompileCmd(String containerId) {
        ExecCreateCmdResponse compileExecCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd("javac", "-encoding", "utf-8", "/app/" + main_name)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        return compileExecCreateCmdResponse;
    }

    @Override
    protected String[] createExecCmd() {
        // 命令： java -cp /app Main 1 2
        // -cp /app 执行类文件所在目录
        return new String[]{"java","-cp","/app","Main"};
    }


}
