package com.baimao.codesandbox.core.docker;


/**
 * 根据语言返回对应 Docker沙箱
 */
public class DockerCodeSandboxFactory {
    public static DockerCodesandboxTemplate getInstance(String language) {
        switch (language) {
            case "java":
                return new JavaDockerCodeSandbox();
            default:
                throw new RuntimeException("暂不支持");
        }
    }
}
