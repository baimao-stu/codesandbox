package com.baimao.codesandbox.core;

import com.baimao.codesandbox.model.CodeSandboxCmd;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Arrays;


/**
 * cpp本机代码沙箱
 */
@Slf4j
public class CppNativeCodeSandbox extends CodesandboxTemplate {

    private static final String PREFIX = File.separator + "cpp";

    private static final String CPP_NAME = File.separator + "main.cpp";

    public CppNativeCodeSandbox(){
        super.prefix = PREFIX;
        super.main_name = CPP_NAME;
    }

    @Override
    public CodeSandboxCmd getCmd(String userCodeParentPath, String userCodePath) {
        String outputFilePath = userCodePath.substring(0, userCodePath.length() - 4) + (isWindows() ? ".exe" : "");
        return CodeSandboxCmd
                .builder()
                .compileCmd(Arrays.asList("g++", "-finput-charset=UTF-8", "-fexec-charset=UTF-8", userCodePath, "-o", outputFilePath))
                .runCmd(Arrays.asList(outputFilePath))
                .build();
    }
}
