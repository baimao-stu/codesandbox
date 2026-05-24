package com.baimao.codesandbox.core.docker;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;

public class JavaDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static final Long TIME_OUT = 10000L;

    public JavaDockerCodeSandbox(SandboxContainerPoolManager poolManager) {
        super(DockerSandboxLanguage.JAVA, poolManager);
        super.timeOut = TIME_OUT;
    }

    @Override
    protected ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir) {
        return dockerClient.execCreateCmd(containerId)
                .withCmd("javac", "-encoding", "utf-8", containerCodeDir + "/" + main_name)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
    }

    @Override
    protected String[] createExecCmd(String containerCodeDir) {
        return new String[]{"java", "-cp", containerCodeDir, "Main"};
    }
}
