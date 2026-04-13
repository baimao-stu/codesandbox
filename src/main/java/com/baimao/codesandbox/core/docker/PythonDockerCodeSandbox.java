package com.baimao.codesandbox.core.docker;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;

import java.io.File;

public class PythonDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static final Long TIME_OUT = 10000L;
    private static final String PREFIX = File.separator + "python";
    private static final String PYTHON_NAME = "Main.py";
    private static final String DOCKER_IMAGE = "python:3.12-alpine";

    public PythonDockerCodeSandbox() {
        super.prefix = PREFIX;
        super.main_name = PYTHON_NAME;
        super.dockerImage = DOCKER_IMAGE;
        super.timeOut = TIME_OUT;
    }

    @Override
    protected ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir) {
        return dockerClient.execCreateCmd(containerId)
                .withCmd("python3", "-m", "py_compile", containerCodeDir + "/" + main_name)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
    }

    @Override
    protected String[] createExecCmd(String containerCodeDir) {
        return new String[]{"python3", containerCodeDir + "/" + main_name};
    }
}
