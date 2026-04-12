package com.baimao.codesandbox.core;


/**
 * 根据语言返回对应沙箱
 */
public class CodeSandboxFactory {
    public static CodesandboxTemplate getInstance(String language) {
        switch (language) {
            case "java":
                return new JavaNativeCodeSandbox();
            case "cpp":
                return new CppNativeCodeSandbox();
            case "python":
                return new PythonNativeCodeSandbox();
            default:
                throw new RuntimeException("暂不支持");
        }
    }
}
