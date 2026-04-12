package com.baimao.codesandbox.core;

import com.baimao.codesandbox.model.CodeSandboxCmd;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;


/**
 * Python本机代码沙箱
 */
@Slf4j
public class PythonNativeCodeSandbox extends CodesandboxTemplate {

    private static final String PREFIX = File.separator + "python";

    private static final String PYTHON_NAME = File.separator + "Main.py";

    public PythonNativeCodeSandbox(){
        super.prefix = PREFIX;
        super.main_name = PYTHON_NAME;
    }

    @Override
    public CodeSandboxCmd getCmd(String userCodeParentPath, String userCodePath) {
        String pythonCommand = isWindows() ? "python" : "python3";
        return CodeSandboxCmd
                .builder()
                .compileCmd(Collections.emptyList())
                .runCmd(Arrays.asList(pythonCommand, userCodePath))
                .build();
    }
}
