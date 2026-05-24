package com.baimao.codesandbox.core.docker;


import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;

public class CppDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static final Long TIME_OUT = 10000L;
    private static final String EXECUTABLE_NAME = "main";

    public CppDockerCodeSandbox(SandboxContainerPoolManager poolManager) {
        super(DockerSandboxLanguage.CPP, poolManager);
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
