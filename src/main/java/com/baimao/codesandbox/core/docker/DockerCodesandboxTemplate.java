package com.baimao.codesandbox.core.docker;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.CodeSandbox;
import com.baimao.codesandbox.docker.DockerClientFactory;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
public abstract class DockerCodesandboxTemplate implements CodeSandbox {

    private static final String USER_DIR = "user.dir";
    private static final String TMP_CODE = "tmpCode";

    protected String prefix;
    protected String main_name;
    protected String dockerImage;
    protected long timeOut;

    final DockerClient dockerClient = DockerClientFactory.createDockerClient();

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();
        String userCodeParentDir = null;
        String containerId = null;

        try {
            userCodeParentDir = saveCodeFile(code);
            containerId = createContainer(dockerImage, userCodeParentDir);
            dockerClient.startContainerCmd(containerId).exec();

            ExecuteCodeResponse compileResponse = compileCodeFile(containerId, userCodeParentDir);
            log.info("compile result = {}", compileResponse);
            if (compileResponse != null) {
                return compileResponse;
            }

            List<ExecuteMessage> executeMessageList = runFile(containerId, inputList);
            return getOutputResponse(executeMessageList);
        } finally {
            if (StrUtil.isNotBlank(containerId)) {
                removeContainer(containerId);
            }
            if (StrUtil.isNotBlank(userCodeParentDir)) {
                boolean deleted = deleteFile(userCodeParentDir);
                if (!deleted) {
                    log.error("delete file failed, path = {}", userCodeParentDir);
                }
            }
        }
    }

    private String createContainer(String dockerImage, String userCodeParentDir) {
//        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(dockerImage);
//        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
//            @Override
//            public void onNext(PullResponseItem item) {
//                log.info("pull image: {}", item.getStatus());
//                super.onNext(item);
//            }
//        };
//        try {
//            pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            log.error("pull image interrupted");
//            throw new RuntimeException(e);
//        }

        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(dockerImage);
        HostConfig hostConfig = new HostConfig();
        hostConfig.setBinds(new Bind(userCodeParentDir, new Volume("/app")));
        hostConfig.withMemory(256 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);

        CreateContainerResponse createContainerResponse = containerCmd
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withHostConfig(hostConfig)
                // Keep the container alive so later docker exec calls can run compile and execute commands.
                .withCmd("sh", "-c", "while true; do sleep 3600; done")
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .exec();
        return createContainerResponse.getId();
    }

    public String saveCodeFile(String code) {
        String tmpCodeFile = System.getProperty(USER_DIR) + File.separator + TMP_CODE + prefix;
        if (!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + main_name;
        File file = FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);
        log.info("save code file success, path = {}", file.getAbsolutePath());
        return userCodeParentDir;
    }

    protected abstract ExecCreateCmdResponse createCompileCmd(String containerId);

    protected abstract String[] createExecCmd();

    public ExecuteCodeResponse compileCodeFile(String containerId, String userCodeParentDir) {
        ExecCreateCmdResponse compileExecCreateCmdResponse = createCompileCmd(containerId);

        ExecuteCodeResponse executeCodeResponse = null;

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
            executeCodeResponse.setMessage("Compile timeout");
            executeCodeResponse.setStatus(3);
            executeCodeResponse.setJudgeInfo(new JudgeInfo());
            if (FileUtil.exist(userCodeParentDir)) {
                FileUtil.del(userCodeParentDir);
            }
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
            if (FileUtil.exist(userCodeParentDir)) {
                FileUtil.del(userCodeParentDir);
            }
            return executeCodeResponse;
        }

        return executeCodeResponse;
    }

    public List<ExecuteMessage> runFile(String containerId, List<String> inputList) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        StopWatch stopWatch = new StopWatch();
        for (String inputArgs : inputList) {
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(createExecCmd(), inputArgsArray);
            log.info("run command: {}", StrUtil.join(" ", cmdArray));

            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String execId = execCreateCmdResponse.getId();
            ExecuteMessage executeMessage = ExecuteMessage.builder().build();
            final String[] outputMessage = {null};
            final String[] errorOutputMessage = {null};
            final long[] maxMemory = {0};

            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    if (ObjUtil.isNotEmpty(outputMessage[0]) || ObjUtil.isNotEmpty(errorOutputMessage[0])) {
                        return;
                    }

                    StreamType streamType = frame.getStreamType();
                    String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                    if (StreamType.STDERR.equals(streamType)) {
                        errorOutputMessage[0] = payload;
                        log.info("stderr: {}", errorOutputMessage[0]);
                    } else {
                        outputMessage[0] = payload;
                        log.info("stdout: {}", outputMessage[0]);
                    }
                    super.onNext(frame);
                }
            };

            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            statsCmd.exec(new ResultCallback<Statistics>() {
                @Override
                public void onNext(Statistics statistics) {
                    Long memoryUsage = statistics.getMemoryStats().getUsage();
                    if (memoryUsage != null) {
                        maxMemory[0] = Math.max(maxMemory[0], memoryUsage);
                    }
                }

                @Override
                public void close() throws IOException {
                }

                @Override
                public void onStart(Closeable closeable) {
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {
                }
            });

            long time = 0;
            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(timeOut, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                statsCmd.close();
                time = stopWatch.getLastTaskTimeMillis();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("run command interrupted");
                throw new RuntimeException(e);
            }

//            try {
//                Thread.sleep(100);
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                throw new RuntimeException(e);
//            }

            executeMessage.setOutputMessage(outputMessage[0]);
            executeMessage.setErrorOutputMessage(errorOutputMessage[0]);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
            log.info("case result: {}", executeMessage);
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

    public boolean deleteFile(String userCodeParentDir) {
        return FileUtil.del(userCodeParentDir);
    }

    private void removeContainer(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("remove container failed, containerId={}", containerId, e);
        }
    }

    private ExecuteCodeResponse getErrorResponse() {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList<>());
        executeCodeResponse.setMessage("Compile error");
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
