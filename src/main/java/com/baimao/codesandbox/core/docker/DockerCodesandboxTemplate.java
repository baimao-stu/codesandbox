package com.baimao.codesandbox.core.docker;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.CodeSandbox;
import com.baimao.codesandbox.core.docker.pool.SandboxBusyException;
import com.baimao.codesandbox.core.docker.pool.SandboxContainer;
import com.baimao.codesandbox.core.docker.pool.SandboxContainerFactory;
import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;
import com.baimao.codesandbox.docker.DockerClientFactory;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public abstract class DockerCodesandboxTemplate implements CodeSandbox {

    private static final String USER_DIR = "user.dir";
    private static final String TMP_CODE = "tmpCode";
    private static final String COMPILE_TIMEOUT_MESSAGE = "Compile timeout";
    private static final String RUN_TIMEOUT_MESSAGE = "Time Limit Exceeded";
    private static final String RUNNER_FILE_NAME = ".sandbox-runner.sh";
    private static final String CASE_FILE_PREFIX = "case-";

    protected final DockerSandboxLanguage sandboxLanguage;
    protected final SandboxContainerPoolManager poolManager;
    protected String prefix;
    protected String main_name;
    protected long timeOut;

    final DockerClient dockerClient = DockerClientFactory.createDockerClient();

    public DockerCodesandboxTemplate(DockerSandboxLanguage sandboxLanguage, SandboxContainerPoolManager poolManager) {
        this.sandboxLanguage = sandboxLanguage;
        this.poolManager = poolManager;
        this.prefix = sandboxLanguage.getPrefix();
        this.main_name = sandboxLanguage.getMainName();
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();
        String userCodeParentDir = null;
        SandboxContainer sandboxContainer = null;
        boolean containerReusable = true;
        StopWatch stopWatch = new StopWatch("executeCode");

        try {
            // 1、保存用户代码为文件
            stopWatch.start("saveCodeFile");
            userCodeParentDir = saveCodeFile(code);
            stopWatch.stop();

            // 2、从对应语言的容器池借出一个空闲容器
            stopWatch.start("acquireContainer");
            sandboxContainer = poolManager.getPool(sandboxLanguage).acquire();
            stopWatch.stop();
            String containerId = sandboxContainer.getContainerId();

            String containerCodeDir = getContainerCodeDir(userCodeParentDir);

            // 3、编译程序
            stopWatch.start("compileCodeFile");
            ExecuteCodeResponse compileResponse = compileCodeFile(containerId, userCodeParentDir, containerCodeDir);
            stopWatch.stop();
            log.debug("compile result = {}", compileResponse);
            if (compileResponse != null) {
                containerReusable = !COMPILE_TIMEOUT_MESSAGE.equals(compileResponse.getMessage());
                return compileResponse;
            }

            // 4、执行程序
            stopWatch.start("runFile");
            List<ExecuteMessage> executeMessageList = runFile(containerId, userCodeParentDir, containerCodeDir, inputList);
            stopWatch.stop();
            containerReusable = !hasTimeout(executeMessageList);
            return getOutputResponse(executeMessageList);
        }
        catch (SandboxBusyException e) {
            log.warn("sandbox container pool busy, language={}", sandboxLanguage.getLanguage());
            return getBusyResponse();
        }
        catch (RuntimeException e) {
            containerReusable = false;
            throw e;
        } finally {
            if (stopWatch.isRunning()) {
                stopWatch.stop();
            }
            log.info("沙箱执行阶段耗时，language={}, total={} ms, stages={}",
                    sandboxLanguage.getLanguage(), stopWatch.getTotalTimeMillis(), buildStageSummary(stopWatch));
            log.debug(stopWatch.prettyPrint());
            if (StrUtil.isNotBlank(userCodeParentDir)) {
                boolean deleted = deleteFile(userCodeParentDir);
                if (!deleted) {
                    log.error("delete file failed, path = {}", userCodeParentDir);
                }
            }
            if (sandboxContainer != null) {
                if (containerReusable) {
                    poolManager.getPool(sandboxLanguage).release(sandboxContainer);
                } else {
                    poolManager.getPool(sandboxLanguage).invalidate(sandboxContainer);
                }
            }
        }
    }

    private String getSandboxHostRoot() {
        return System.getProperty(USER_DIR) + File.separator + TMP_CODE + prefix;
    }

    private String getContainerCodeDir(String userCodeParentDir) {
        return SandboxContainerFactory.CONTAINER_WORKSPACE + "/" + new File(userCodeParentDir).getName();
    }

    public String saveCodeFile(String code) {
        String tmpCodeFile = getSandboxHostRoot();
        if (!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + main_name;
        File file = FileUtil.writeString(code == null ? "" : code, userCodeFile, StandardCharsets.UTF_8);
        log.debug("save code file success, path = {}", file.getAbsolutePath());
        return userCodeParentDir;
    }

    protected abstract ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir);

    protected abstract String[] createExecCmd(String containerCodeDir);

    public ExecuteCodeResponse compileCodeFile(String containerId, String userCodeParentDir, String containerCodeDir) {
        ExecCreateCmdResponse compileExecCreateCmdResponse = createCompileCmd(containerId, containerCodeDir);

        ExecuteCodeResponse executeCodeResponse = null;
        if(compileExecCreateCmdResponse == null) {
            return executeCodeResponse;
        }

        String compileExecId = compileExecCreateCmdResponse.getId();
        final StringBuilder compileOutput = new StringBuilder();
        final StringBuilder compileError = new StringBuilder();
        final boolean[] compileTimeout = {true};

        ExecStartResultCallback compileResultCallback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                String message = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (StreamType.STDERR.equals(frame.getStreamType())) {
                    compileError.append(message);
                } else {
                    compileOutput.append(message);
                }
                super.onNext(frame);
            }

            @Override
            public void onComplete() {
                compileTimeout[0] = false;
                super.onComplete();
            }
        };

        try {
            dockerClient.execStartCmd(compileExecId)
                    .exec(compileResultCallback)
                    .awaitCompletion(timeOut, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return getErrorResponse();
        }

        if (compileTimeout[0]) {
            executeCodeResponse = new ExecuteCodeResponse();
            executeCodeResponse.setOutput(new ArrayList<>());
            executeCodeResponse.setMessage(COMPILE_TIMEOUT_MESSAGE);
            executeCodeResponse.setStatus(3);
            executeCodeResponse.setJudgeInfo(new JudgeInfo());
            return executeCodeResponse;
        }

        InspectExecResponse compileInspect = dockerClient.inspectExecCmd(compileExecId).exec();
        Long compileExitCode = compileInspect.getExitCodeLong();
        if (compileExitCode != null && compileExitCode != 0) {
            executeCodeResponse = new ExecuteCodeResponse();
            executeCodeResponse.setOutput(new ArrayList<>());
            executeCodeResponse.setMessage(StrUtil.isNotBlank(compileError.toString()) ? compileError.toString() : compileOutput.toString());
            executeCodeResponse.setStatus(3);
            executeCodeResponse.setJudgeInfo(new JudgeInfo());
            return executeCodeResponse;
        }

        return executeCodeResponse;
    }

    public List<ExecuteMessage> runFile(String containerId, String userCodeParentDir, String containerCodeDir, List<String> inputList) {
        if (inputList == null || inputList.isEmpty()) {
            return new ArrayList<>();
        }
        createBatchRunnerFiles(userCodeParentDir, containerCodeDir, inputList);
        return runBatchCases(containerId, userCodeParentDir, containerCodeDir, inputList.size());
    }

    private List<ExecuteMessage> runBatchCases(String containerId, String userCodeParentDir, String containerCodeDir, int caseCount) {
        String[] cmdArray = new String[]{"sh", containerCodeDir + "/" + RUNNER_FILE_NAME};
        log.debug("run command: {}", StrUtil.join(" ", cmdArray));

        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        String execId = execCreateCmdResponse.getId();
        final StringBuilder runnerOutputMessage = new StringBuilder();
        final StringBuilder runnerErrorMessage = new StringBuilder();
        final boolean[] runCompleted = {false};

        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            // 执行批量 runner 的回调，只收集 runner 自身异常，用户代码输出写入独立文件。
            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (StreamType.STDERR.equals(streamType)) {
                    runnerErrorMessage.append(payload);
                } else {
                    runnerOutputMessage.append(payload);
                }
                super.onNext(frame);
            }

            @Override
            public void onComplete() {
                runCompleted[0] = true;
                super.onComplete();
            }
        };

        long time = 0;
        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(getBatchTimeoutMillis(caseCount), TimeUnit.MILLISECONDS);
            stopWatch.stop();
            time = stopWatch.getLastTaskTimeMillis();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("run command interrupted");
            throw new RuntimeException(e);
        }

        if (!runCompleted[0]) {
            return buildBatchTimeoutMessages(caseCount);
        }

        InspectExecResponse runInspect = dockerClient.inspectExecCmd(execId).exec();
        Long runnerExitCode = runInspect.getExitCodeLong();
        if (runnerExitCode != null && runnerExitCode != 0) {
            return buildBatchRunnerErrorMessages(caseCount, runnerErrorMessage, runnerOutputMessage, runnerExitCode);
        }

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        long fallbackTime = caseCount == 0 ? time : Math.max(1L, time / caseCount);
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            ExecuteMessage executeMessage = buildCaseMessage(userCodeParentDir, caseIndex, fallbackTime);
            executeMessageList.add(executeMessage);
            log.debug("case result: {}", executeMessage);
        }
        return executeMessageList;
    }

    private void createBatchRunnerFiles(String userCodeParentDir, String containerCodeDir, List<String> inputList) {
        for (int caseIndex = 0; caseIndex < inputList.size(); caseIndex++) {
            String inputContent = StrUtil.isBlank(inputList.get(caseIndex)) ? "" : inputList.get(caseIndex) + "\n";
            FileUtil.writeString(inputContent, getCaseHostPath(userCodeParentDir, caseIndex, "in"), StandardCharsets.UTF_8);
        }
        FileUtil.writeString(buildBatchRunnerScript(containerCodeDir, inputList.size()),
                userCodeParentDir + File.separator + RUNNER_FILE_NAME, StandardCharsets.UTF_8);
    }

    private String buildBatchRunnerScript(String containerCodeDir, int caseCount) {
        String timeoutSeconds = String.valueOf(Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(timeOut)));
        String execCommand = shellJoin(createExecCmd(containerCodeDir));
        String timedExecCommand = "timeout " + timeoutSeconds + "s " + execCommand;
        StringBuilder scriptBuilder = new StringBuilder();
        scriptBuilder.append("#!/bin/sh\n");
        scriptBuilder.append("run_case() {\n");
        scriptBuilder.append("  input_file=\"$1\"\n");
        scriptBuilder.append("  output_file=\"$2\"\n");
        scriptBuilder.append("  error_file=\"$3\"\n");
        scriptBuilder.append("  time_file=\"$4\"\n");
        scriptBuilder.append("  exit_file=\"$5\"\n");
        // 每个测试用例仍保持独立进程，只把 Docker exec 往返合并成一次。
        scriptBuilder.append("  if /usr/bin/time -p -o \"$time_file\" true >/dev/null 2>/dev/null; then\n");
        scriptBuilder.append("    /usr/bin/time -p -o \"$time_file\" ")
                .append(timedExecCommand)
                .append(" < \"$input_file\" > \"$output_file\" 2> \"$error_file\"\n");
        scriptBuilder.append("  else\n");
        // 部分极简镜像没有支持 -o 的 time，回退时 Java 侧会清理混入 stderr 的耗时行。
        scriptBuilder.append("    ( time -p ").append(timedExecCommand)
                .append(" < \"$input_file\" > \"$output_file\" 2> \"$error_file\" ) 2> \"$time_file\"\n");
        scriptBuilder.append("  fi\n");
        scriptBuilder.append("  exit_code=$?\n");
        scriptBuilder.append("  printf '%s' \"$exit_code\" > \"$exit_file\"\n");
        scriptBuilder.append("}\n");
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            scriptBuilder.append("run_case ")
                    .append(shellQuote(getCaseContainerPath(containerCodeDir, caseIndex, "in"))).append(" ")
                    .append(shellQuote(getCaseContainerPath(containerCodeDir, caseIndex, "out"))).append(" ")
                    .append(shellQuote(getCaseContainerPath(containerCodeDir, caseIndex, "err"))).append(" ")
                    .append(shellQuote(getCaseContainerPath(containerCodeDir, caseIndex, "time"))).append(" ")
                    .append(shellQuote(getCaseContainerPath(containerCodeDir, caseIndex, "exit"))).append("\n");
        }
        return scriptBuilder.toString();
    }

    private ExecuteMessage buildCaseMessage(String userCodeParentDir, int caseIndex, long fallbackTime) {
        String outputMessage = readCaseFile(userCodeParentDir, caseIndex, "out");
        String rawErrorOutputMessage = readCaseFile(userCodeParentDir, caseIndex, "err");
        String exitContent = readCaseFile(userCodeParentDir, caseIndex, "exit");
        Integer exitCode = parseExitCode(exitContent);
        long caseTime = parseCaseTimeMillis(readCaseFile(userCodeParentDir, caseIndex, "time"), rawErrorOutputMessage, fallbackTime);
        String errorOutputMessage = cleanCaseErrorOutput(rawErrorOutputMessage);

        ExecuteMessage executeMessage = ExecuteMessage.builder().build();
        executeMessage.setWaitValue(exitCode);
        executeMessage.setOutputMessage(outputMessage == null || outputMessage.length() == 0 ? null : outputMessage);
        executeMessage.setTime(isTimeoutExitCode(exitCode) ? timeOut : caseTime);
        executeMessage.setMemory(0L);
        if (isTimeoutExitCode(exitCode)) {
            executeMessage.setTimeout(true);
            executeMessage.setErrorOutputMessage(RUN_TIMEOUT_MESSAGE);
        } else if (isRuntimeErrorExitCode(exitCode)) {
            String runtimeErrorMessage = StrUtil.isBlank(errorOutputMessage)
                    ? "Process exited with code " + exitCode
                    : errorOutputMessage;
            executeMessage.setErrorOutputMessage(runtimeErrorMessage);
        } else {
            executeMessage.setErrorOutputMessage(StrUtil.isBlank(errorOutputMessage) ? null : errorOutputMessage);
        }
        return executeMessage;
    }

    private List<ExecuteMessage> buildBatchTimeoutMessages(int caseCount) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            ExecuteMessage executeMessage = ExecuteMessage.builder().build();
            executeMessage.setTimeout(true);
            executeMessage.setErrorOutputMessage(RUN_TIMEOUT_MESSAGE);
            executeMessage.setTime(timeOut);
            executeMessage.setMemory(0L);
            executeMessageList.add(executeMessage);
        }
        return executeMessageList;
    }

    private List<ExecuteMessage> buildBatchRunnerErrorMessages(int caseCount,
                                                               StringBuilder runnerErrorMessage,
                                                               StringBuilder runnerOutputMessage,
                                                               Long runnerExitCode) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < caseCount; caseIndex++) {
            ExecuteMessage executeMessage = ExecuteMessage.builder().build();
            executeMessage.setWaitValue(runnerExitCode.intValue());
            String runnerMessage = StrUtil.isNotBlank(runnerErrorMessage.toString())
                    ? runnerErrorMessage.toString()
                    : runnerOutputMessage.toString();
            if (StrUtil.isBlank(runnerMessage)) {
                runnerMessage = "Runner exited with code " + runnerExitCode;
            }
            executeMessage.setErrorOutputMessage(runnerMessage);
            executeMessage.setTime(timeOut);
            executeMessage.setMemory(0L);
            executeMessageList.add(executeMessage);
        }
        return executeMessageList;
    }

    private String getCaseHostPath(String userCodeParentDir, int caseIndex, String suffix) {
        return userCodeParentDir + File.separator + getCaseFileName(caseIndex, suffix);
    }

    private String getCaseContainerPath(String containerCodeDir, int caseIndex, String suffix) {
        return containerCodeDir + "/" + getCaseFileName(caseIndex, suffix);
    }

    private String getCaseFileName(int caseIndex, String suffix) {
        return CASE_FILE_PREFIX + caseIndex + "." + suffix;
    }

    private String readCaseFile(String userCodeParentDir, int caseIndex, String suffix) {
        String caseFilePath = getCaseHostPath(userCodeParentDir, caseIndex, suffix);
        if (!FileUtil.exist(caseFilePath)) {
            return null;
        }
        return FileUtil.readString(caseFilePath, StandardCharsets.UTF_8);
    }

    private Integer parseExitCode(String exitContent) {
        if (StrUtil.isBlank(exitContent)) {
            return null;
        }
        try {
            return Integer.parseInt(exitContent.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long parseCaseTimeMillis(String timeContent, String fallbackTimeContent, long fallbackTime) {
        Long parsedTime = parseCaseTimeMillis(timeContent);
        if (parsedTime != null) {
            return parsedTime;
        }
        parsedTime = parseCaseTimeMillis(fallbackTimeContent);
        if (parsedTime != null) {
            return parsedTime;
        }
        return fallbackTime;
    }

    private Long parseCaseTimeMillis(String timeContent) {
        if (StrUtil.isBlank(timeContent)) {
            return null;
        }
        for (String line : timeContent.split("\\R")) {
            String trimLine = line.trim();
            if (trimLine.startsWith("real ")) {
                try {
                    return Math.max(1L, Math.round(Double.parseDouble(trimLine.substring(5).trim()) * 1000));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String cleanCaseErrorOutput(String errorOutputMessage) {
        if (StrUtil.isBlank(errorOutputMessage)) {
            return null;
        }
        List<String> cleanedLines = Arrays.stream(errorOutputMessage.split("\\R"))
                // 兼容部分 busybox time 把耗时写入 stderr 的情况，避免污染业务错误信息。
                .filter(line -> !isTimeOutputLine(line))
                .collect(Collectors.toList());
        String cleanedMessage = String.join("\n", cleanedLines);
        if (errorOutputMessage.endsWith("\n") && StrUtil.isNotBlank(cleanedMessage)) {
            cleanedMessage += "\n";
        }
        return StrUtil.isBlank(cleanedMessage) ? null : cleanedMessage;
    }

    private boolean isTimeOutputLine(String line) {
        String trimLine = line == null ? "" : line.trim();
        return trimLine.startsWith("real ")
                || trimLine.startsWith("user ")
                || trimLine.startsWith("sys ")
                || trimLine.startsWith("Command exited with non-zero status ");
    }

    private boolean isTimeoutExitCode(Integer exitCode) {
        return exitCode != null && (exitCode == 124 || exitCode == 137 || exitCode == 143);
    }

    private boolean isRuntimeErrorExitCode(Integer exitCode) {
        // 非 0 退出码代表用户程序运行异常，即使 stderr 为空也要返回运行错误。
        return exitCode != null && exitCode != 0;
    }

    private long getBatchTimeoutMillis(int caseCount) {
        // runner 内部按单用例限制超时，这里只给 Docker exec 留出收尾缓冲。
        return (timeOut + 1000L) * Math.max(1, caseCount);
    }

    private String shellJoin(String[] commandParts) {
        return Arrays.stream(commandParts)
                .map(this::shellQuote)
                .collect(Collectors.joining(" "));
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            if (Boolean.TRUE.equals(executeMessage.getTimeout())) {
                executeCodeResponse.setMessage(RUN_TIMEOUT_MESSAGE);
                executeCodeResponse.setStatus(3);
                break;
            }
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

    public boolean deleteFile(String userCodeParentDir) {
        return FileUtil.del(userCodeParentDir);
    }

    private boolean hasTimeout(List<ExecuteMessage> executeMessageList) {
        for (ExecuteMessage executeMessage : executeMessageList) {
            if (Boolean.TRUE.equals(executeMessage.getTimeout())) {
                return true;
            }
        }
        return false;
    }

    private String buildStageSummary(StopWatch stopWatch) {
        // 输出紧凑的阶段耗时，方便在压测日志中快速定位瓶颈。
        return Arrays.stream(stopWatch.getTaskInfo())
                .map(taskInfo -> taskInfo.getTaskName() + "=" + taskInfo.getTimeMillis() + "ms")
                .collect(Collectors.joining(", "));
    }

    private ExecuteCodeResponse getErrorResponse() {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList<>());
        executeCodeResponse.setMessage("Compile error");
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

    private ExecuteCodeResponse getBusyResponse() {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList<>());
        executeCodeResponse.setMessage("Sandbox busy");
        executeCodeResponse.setStatus(3);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
