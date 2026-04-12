package com.baimao.codesandbox.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.CodeSandbox;
import com.baimao.codesandbox.model.CodeSandboxCmd;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.baimao.codesandbox.utils.ProcessUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
public abstract class CodesandboxTemplate implements CodeSandbox {

    private static final String USER_DIR = "user.dir";
    private static final String TMP_CODE = "tmpCode";
    private static final long TIME_OUT = 10000L;

    protected String prefix;
    protected String main_name;

    protected boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    protected abstract CodeSandboxCmd getCmd(String userCodeParentPath, String userCodePath);

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();

        File codeFile = saveCodeFile(code);
        String codeAbsolutePath = codeFile.getAbsolutePath();
        String codeParentAbsolutePath = codeFile.getParentFile().getAbsolutePath();
        CodeSandboxCmd codeSandboxCmd = getCmd(codeParentAbsolutePath, codeAbsolutePath);

        ExecuteMessage executeCompileMessage;
        ExecuteCodeResponse outputResponse;
        try {
            executeCompileMessage = compileCodeFile(codeFile, codeSandboxCmd);
            if (executeCompileMessage.getWaitValue() != 0) {
                outputResponse = getErrorResponse();
                deleteFile(codeFile);
                return outputResponse;
            }
        } catch (IOException e) {
            outputResponse = getErrorResponse();
            deleteFile(codeFile);
            return outputResponse;
        }
        log.info("compile result: {}", executeCompileMessage);

        List<ExecuteMessage> executeMessageList = runFile(codeFile, inputList, codeSandboxCmd);
        outputResponse = getOutputResponse(executeMessageList);

        boolean deleted = deleteFile(codeFile);
        if (!deleted) {
            log.error("deleteFile error, userCodeFile Path = {}", codeFile.getAbsolutePath());
        }
        return outputResponse;
    }

    public File saveCodeFile(String code) {
        String tmpCodeFile = System.getProperty(USER_DIR) + File.separator + TMP_CODE + prefix;
        if (!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + main_name;
        return FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);
    }

    public ExecuteMessage compileCodeFile(File file, CodeSandboxCmd codeSandboxCmd) throws IOException {
        List<String> compileCmd = codeSandboxCmd.getCompileCmd();
        if (compileCmd == null || compileCmd.isEmpty()) {
            return ExecuteMessage.builder().waitValue(0).build();
        }
        Process compileProcess = new ProcessBuilder(compileCmd).start();
        return ProcessUtil.handleProcessMessage(compileProcess, "compile");
    }

    public List<ExecuteMessage> runFile(File codeFile, List<String> inputList, CodeSandboxCmd codeSandboxCmd) {
        String userCodeParentDir = codeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        List<String> runCmd = codeSandboxCmd.getRunCmd();
        for (String inputArgs : inputList) {
            try {
                Process runProcess = new ProcessBuilder(runCmd)
                        .directory(new File(userCodeParentDir))
                        .start();
                Thread computeTimeThread = new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        log.info("timeout reached, terminating process");
                        if (runProcess.isAlive()) {
                            runProcess.destroy();
                        }
                    } catch (InterruptedException e) {
                        log.info("process timer interrupted: {}", e.getMessage());
                    }
                });
                computeTimeThread.start();

                StopWatch stopWatch = new StopWatch();
                stopWatch.start();
                ExecuteMessage executeMessage = ProcessUtil.handleProcessInteraction(runProcess, inputArgs, "run");
                stopWatch.stop();

                executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
                computeTimeThread.interrupt();
                executeMessage.setMemory(0L);
                executeMessageList.add(executeMessage);
            } catch (IOException e) {
                deleteFile(codeFile);
                throw new RuntimeException("run error", e);
            }
        }
        return executeMessageList;
    }

    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorOutputMessage = executeMessage.getErrorOutputMessage();
            if (StrUtil.isNotBlank(errorOutputMessage)) {
                executeCodeResponse.setMessage(errorOutputMessage);
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getOutputMessage());
            Long time = executeMessage.getTime();
            Long memory = executeMessage.getMemory();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
        }
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutput(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(maxMemory);
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    public boolean deleteFile(File codeFile) {
        String userCodeParentDir = codeFile.getParentFile().getAbsolutePath();
        if (FileUtil.exist(userCodeParentDir)) {
            boolean deleted = FileUtil.del(userCodeParentDir);
            log.info("delete user code files: {}", deleted ? "success" : "failed");
            return deleted;
        }
        return true;
    }

    private ExecuteCodeResponse getErrorResponse() {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList());
        executeCodeResponse.setMessage("Compile error");
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
