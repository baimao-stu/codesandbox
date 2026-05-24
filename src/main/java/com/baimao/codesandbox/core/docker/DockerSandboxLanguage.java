package com.baimao.codesandbox.core.docker;

import java.io.File;

/**
 * Docker 沙箱支持的语言定义，集中维护语言、目录、入口文件和镜像信息。
 */
public enum DockerSandboxLanguage {

    JAVA("java", File.separator + "java", "Main.java", "eclipse-temurin:21-jdk-alpine"),
    CPP("cpp", File.separator + "cpp", "main.cpp", "gcc:13.2"),
    PYTHON("python", File.separator + "python", "Main.py", "python:3.12-alpine");

    private final String language;
    private final String prefix;
    private final String mainName;
    private final String dockerImage;

    DockerSandboxLanguage(String language, String prefix, String mainName, String dockerImage) {
        this.language = language;
        this.prefix = prefix;
        this.mainName = mainName;
        this.dockerImage = dockerImage;
    }

    public String getLanguage() {
        return language;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getMainName() {
        return mainName;
    }

    public String getDockerImage() {
        return dockerImage;
    }
}
