package com.baimao.codesandbox.core.docker;

import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;
import org.springframework.stereotype.Component;

@Component
public class DockerCodeSandboxFactory {

    private final JavaDockerCodeSandbox javaInstance;
    private final CppDockerCodeSandbox cppInstance;
    private final PythonDockerCodeSandbox pythonInstance;

    public DockerCodeSandboxFactory(SandboxContainerPoolManager poolManager) {
        this.javaInstance = new JavaDockerCodeSandbox(poolManager);
        this.cppInstance = new CppDockerCodeSandbox(poolManager);
        this.pythonInstance = new PythonDockerCodeSandbox(poolManager);
    }

    public DockerCodesandboxTemplate getInstance(String language) {
        switch (language) {
            case "java":
                return javaInstance;
            case "cpp":
                return cppInstance;
            case "python":
                return pythonInstance;
            default:
                throw new RuntimeException("Unsupported language");
        }
    }
}
