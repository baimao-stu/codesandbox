package com.baimao.codesandbox.core;

import com.baimao.codesandbox.model.CodeSandboxCmd;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Arrays;


/**
 * java本机代码沙箱
 */
@Slf4j
public class JavaNativeCodeSandbox extends CodesandboxTemplate {

    private static final String PREFIX = File.separator + "java";

    private static final String JAVA_NAME = File.separator + "Main.java";

    public JavaNativeCodeSandbox(){
        super.prefix = PREFIX;
        super.main_name = JAVA_NAME;
    }

    @Override
    public CodeSandboxCmd getCmd(String userCodeParentPath, String userCodePath) {
        return CodeSandboxCmd
                .builder()
                .compileCmd(Arrays.asList("javac", "-encoding", "utf-8", userCodePath))
                .runCmd(Arrays.asList("java", "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", userCodeParentPath, "Main"))
                .build();
    }
}
