package com.baimao.codesandbox.core.docker;

import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;

public class PythonDockerCodeSandbox extends DockerCodesandboxTemplate {

    private static final Long TIME_OUT = 10000L;

    public PythonDockerCodeSandbox(SandboxContainerPoolManager poolManager) {
        super(DockerSandboxLanguage.PYTHON, poolManager);
        super.timeOut = TIME_OUT;
    }

    @Override
    protected ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir) {
        //  return dockerClient.execCreateCmd(containerId)
        //          .withCmd("python3", "-m", "py_compile", containerCodeDir + "/" + main_name)
        //          .withAttachStdout(true)
        //          .withAttachStderr(true)
        //          .exec();
        return null;
    }

    @Override
    protected String[] createExecCmd(String containerCodeDir) {
        return new String[]{"python3", containerCodeDir + "/" + main_name};
    }
}
