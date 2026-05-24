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
            userCodeParentDir = saveCodeFile(code);

            // 2、从对应语言的容器池借出一个空闲容器
            sandboxContainer = poolManager.getPool(sandboxLanguage).acquire();
            String containerId = sandboxContainer.getContainerId();

            String containerCodeDir = getContainerCodeDir(userCodeParentDir);

            // 3、编译程序
            ExecuteCodeResponse compileResponse = compileCodeFile(containerId, userCodeParentDir, containerCodeDir);
            log.info("compile result = {}", compileResponse);
            if (compileResponse != null) {
                containerReusable = !COMPILE_TIMEOUT_MESSAGE.equals(compileResponse.getMessage());
                return compileResponse;
            }

            // 4、执行程序
            stopWatch.start("runFile");
            List<ExecuteMessage> executeMessageList = runFile(containerId, containerCodeDir, inputList);
            stopWatch.stop();
            containerReusable = !hasTimeout(executeMessageList);
            return getOutputResponse(executeMessageList);
        }
//        catch (SandboxBusyException e) {
//            return getBusyResponse();
//        }
        catch (RuntimeException e) {
            containerReusable = false;
            throw e;
        } finally {
            log.info("程序执行所有样例时间： = {} ms", stopWatch.getTotalTimeMillis());
//            log.info(stopWatch.prettyPrint());
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
        File file = FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);
        log.info("save code file success, path = {}", file.getAbsolutePath());
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

    public List<ExecuteMessage> runFile(String containerId, String containerCodeDir, List<String> inputList) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            ExecuteMessage executeMessage = runSingleCase(containerId, containerCodeDir, inputArgs);
            executeMessageList.add(executeMessage);
            log.info("case result: {}", executeMessage);
        }
        return executeMessageList;
    }

    private ExecuteMessage runSingleCase(String containerId, String containerCodeDir, String inputArgs) {
        String[] cmdArray = buildExecCommand(containerCodeDir, inputArgs);
        log.info("run command: {}", StrUtil.join(" ", cmdArray));

        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        String execId = execCreateCmdResponse.getId();
        ExecuteMessage executeMessage = ExecuteMessage.builder().build();
        final StringBuilder outputMessage = new StringBuilder();
        final StringBuilder errorOutputMessage = new StringBuilder();
        final long[] maxMemory = {0};
        final boolean[] runCompleted = {false};
//        final CountDownLatch firstStatsLatch = new CountDownLatch(1);
//        final AtomicBoolean statsClosed = new AtomicBoolean(false);

        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            // 执行 exec 命令的回调，返回的信息是一次一部分，在onNext里收集
            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (StreamType.STDERR.equals(streamType)) {
                    errorOutputMessage.append(payload);
                } else {
                    outputMessage.append(payload);
                }
                super.onNext(frame);
            }

            @Override
            public void onComplete() {
                runCompleted[0] = true;
                super.onComplete();
            }
        };

//        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
//        statsCmd.exec(new ResultCallback<Statistics>() {
//            @Override
//            public void onStart(java.io.Closeable closeable) {
//            }
//
//            @Override
//            public void onNext(Statistics statistics) {
//                if (statsClosed.get()) {
//                    return;
//                }
//                Long memoryUsage = statistics.getMemoryStats() == null ? null : statistics.getMemoryStats().getUsage();
//                if (memoryUsage != null) {
//                    maxMemory[0] = Math.max(maxMemory[0], memoryUsage);
//                }
//                firstStatsLatch.countDown();
//            }
//
//            @Override
//            public void onError(Throwable throwable) {
//                firstStatsLatch.countDown();
//                log.debug("stats stream error for container {}", containerId, throwable);
//            }
//
//            @Override
//            public void onComplete() {
//                firstStatsLatch.countDown();
//            }
//
//            @Override
//            public void close() throws IOException {
//            }
//        });

        long time = 0;
        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(timeOut, TimeUnit.MILLISECONDS);
            stopWatch.stop();
            time = stopWatch.getLastTaskTimeMillis();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("run command interrupted");
            throw new RuntimeException(e);
        } finally {
//            try {
//                if (maxMemory[0] == 0) {
//                    firstStatsLatch.await(50, TimeUnit.MILLISECONDS);
//                }
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//            }
//            statsClosed.set(true);
//            statsCmd.close();
        }

        if (runCompleted[0]) {
            InspectExecResponse runInspect = dockerClient.inspectExecCmd(execId).exec();
            Long exitCode = runInspect.getExitCodeLong();
            if (exitCode != null) {
                executeMessage.setWaitValue(exitCode.intValue());
                if (exitCode != 0 && errorOutputMessage.length() == 0) {
                    errorOutputMessage.append("Process exited with code ").append(exitCode);
                }
            }
        } else {
            executeMessage.setTimeout(true);
            errorOutputMessage.append(RUN_TIMEOUT_MESSAGE);
            time = timeOut;
        }

        executeMessage.setOutputMessage(outputMessage.length() == 0 ? null : outputMessage.toString());
        executeMessage.setErrorOutputMessage(errorOutputMessage.length() == 0 ? null : errorOutputMessage.toString());
        executeMessage.setTime(time);
        executeMessage.setMemory(maxMemory[0]);
        return executeMessage;
    }

    private String[] buildExecCommand(String containerCodeDir, String inputArgs) {
        String execCommand = shellJoin(createExecCmd(containerCodeDir));
        if (StrUtil.isBlank(inputArgs)) {
            return new String[]{"sh", "-c", execCommand};
        }
        String pipedCommand = "printf '%s' " + shellQuote(inputArgs + "\n") + " | " + execCommand;
        return new String[]{"sh", "-c", pipedCommand};
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
