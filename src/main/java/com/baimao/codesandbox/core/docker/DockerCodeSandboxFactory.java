package com.baimao.codesandbox.core.docker;

public class DockerCodeSandboxFactory {

    private static final JavaDockerCodeSandbox JAVA_INSTANCE = new JavaDockerCodeSandbox();
    private static final CppDockerCodeSandbox CPP_INSTANCE = new CppDockerCodeSandbox();
    private static final PythonDockerCodeSandbox PYTHON_INSTANCE = new PythonDockerCodeSandbox();

    public static DockerCodesandboxTemplate getInstance(String language) {
        switch (language) {
            case "java":
                return JAVA_INSTANCE;
            case "cpp":
                return CPP_INSTANCE;
            case "python":
                return PYTHON_INSTANCE;
            default:
                throw new RuntimeException("Unsupported language");
        }
    }
}
