package com.baimao.codesandbox.core.docker;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;

import java.io.File;

public class CppDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static final Long TIME_OUT = 10000L;
    private static final String PREFIX = File.separator + "cpp";
    private static final String CPP_NAME = "main.cpp";
    private static final String EXECUTABLE_NAME = "main";
    private static final String DOCKER_IMAGE = "gcc:13.2";

    public CppDockerCodeSandbox() {
        super.prefix = PREFIX;
        super.main_name = CPP_NAME;
        super.dockerImage = DOCKER_IMAGE;
        super.timeOut = TIME_OUT;
    }

    @Override
    protected ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir) {
        return dockerClient.execCreateCmd(containerId)
                .withCmd(
                        "g++",
                        "-finput-charset=UTF-8",
                        "-fexec-charset=UTF-8",
                        containerCodeDir + "/" + main_name,
                        "-o",
                        containerCodeDir + "/" + EXECUTABLE_NAME
                )
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
    }

    @Override
    protected String[] createExecCmd(String containerCodeDir) {
        return new String[]{containerCodeDir + "/" + EXECUTABLE_NAME};
    }
}
