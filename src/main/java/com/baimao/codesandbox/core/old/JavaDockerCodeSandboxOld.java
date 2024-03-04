package com.baimao.codesandbox.core.old;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.baimao.codesandbox.core.old.utils.ProcessUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author baimao
 * @title JavaNativeCodeSandbox
 */
public class JavaDockerCodeSandboxOld extends JavaCodesandboxTemplate {

    private final static String USER_DIR = "user.dir";

    private final static String TMP_CODE = "tmpCode";

    private final static String CLASS_NAME = "Main.java";

    private static Boolean FIRST_PULL_IMAGE = true;      //是否是初次拉取镜像

    private static final Long TIME_OUT = 2000L; //容器超时时间

    public static void main(String[] args) {
        JavaDockerCodeSandboxOld javaNativeCodeSandbox = new JavaDockerCodeSandboxOld();
        String filePath = "simpleComputeArgs" + File.separator + CLASS_NAME;
        String code = ResourceUtil.readStr(filePath, StandardCharsets.UTF_8);
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setInput(Arrays.asList("1 9","3 4"));
        executeCodeRequest.setLanguage("java");
        javaNativeCodeSandbox.executeCode(executeCodeRequest);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();

        /**1. 将用户代码保存为文件，文件名为Main.java，文件里的主类务必为 Main */
        // 存放用户代码的目录
        String tmpCodeFile = System.getProperty(USER_DIR) + File.separator + TMP_CODE;
        if(!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        // 由于每个文件都命名为 Main.java，因此为每个文件都新建一个目录
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + CLASS_NAME;

        File file = FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);

        /**2. 编译代码 */
        //控制台要执行的命令（编译）
        String compileCmd = String.format("javac -encoding utf-8 %s",file.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtil.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        /** 3. 拉取Docker镜像、创建容器*/
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        String image = "openjdk:8-alpine";
//        if(FIRST_PULL_IMAGE) {
//            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
//            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
//                @Override
//                public void onNext(PullResponseItem item) {
//                    System.out.println("下载镜像：" + item.getStatus());
//                    super.onNext(item);
//                }
//            };
//            try {
//                pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
//            } catch (InterruptedException e) {
//                System.out.println("拉取镜像异常");
//                throw new RuntimeException(e);
//            }
//            System.out.println("镜像拉取完成");
//            FIRST_PULL_IMAGE = false;
//        }
        /**
         *  创建容器：
         *  开启与容器的交互：
         *      .withAttachStdin(true)
         *      .withAttachStdout(true)
         *      .withAttachStderr(true)
         *      .withTty(true)
         *  并将上面的编译好的 class 文件复制到容器中
         */
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        /** 挂载容器数据卷，将主机userCodeParentDir目录下的数据与容器内部/app目录下的数据同步映射 */
        hostConfig.setBinds(new Bind(userCodeParentDir,new Volume("/app")));
        /** 限制容器内存大小256M，cpu 1个 */
        hostConfig.withMemory(256 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);
        //todo 扩展，可以学习一下 linux 内核的安全机制 secomp
//        hostConfig.withSecurityOpts(Arrays.asList("secomp=安全管理配置字符串"));
        CreateContainerResponse createContainerResponse = containerCmd
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withHostConfig(hostConfig)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .exec();
        String containId = createContainerResponse.getId();
        /**
         * 4. 启动容器，执行代码，获取输出结果
         */
        dockerClient.startContainerCmd(containId).exec();
        // 要执行的命令eg：docker exec 64a969d5de10 java -cp /app Main 1 2
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        StopWatch stopWatch = new StopWatch();
        // 测试多个输入用例
        for (String inputArgs : inputList) {
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java","-cp","/app","Main"},inputArgsArray);
            System.out.println("要执行的命令：" + StrUtil.join(" ",cmdArray));
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containId)
                    .withCmd(cmdArray)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            /** 保存输出结果 */
            String execId = execCreateCmdResponse.getId();
            ExecuteMessage executeMessage = ExecuteMessage.builder().build();
            final String[] outputMessage = {null};
            final String[] errorOutputMessage = {null};
            final long[] maxMemory = {0};   //本次测试用例执行过程中检测到的最大内存占用
            final boolean[] timeout = {true}; //是否超时，默认true
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback(){
                @Override
                public void onNext(Frame frame) {
                    if(ObjUtil.isNotEmpty(outputMessage[0]) || ObjUtil.isNotEmpty(errorOutputMessage[0])) return;

                    StreamType streamType = frame.getStreamType();
                    if(StreamType.STDERR.equals(streamType)) {
                        errorOutputMessage[0] = new String(frame.getPayload());
                        System.out.println("输出错误结果：" + errorOutputMessage[0]);
                    }else {
                        outputMessage[0] = new String(frame.getPayload());
                        System.out.println("输出结果：" + outputMessage[0]);
                    }
                    super.onNext(frame);
                }

                @Override
                public void onComplete() {
                    timeout[0] = false;
                    super.onComplete();
                }
            };

            /** 启动状态监控：获取内存使用情况 */
            StatsCmd statsCmd = dockerClient.statsCmd(containId);
            statsCmd.exec(new ResultCallback<Statistics>(){
                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("要执行的命令：" + StrUtil.join(" ",cmdArray));
                    Long memoryUsage = statistics.getMemoryStats().getUsage();
                    System.out.println("内存占用：" + memoryUsage);
                    maxMemory[0] = Math.max(maxMemory[0], memoryUsage);
                }
                @Override
                public void close() throws IOException {}
                @Override
                public void onStart(Closeable closeable) {}
                @Override
                public void onError(Throwable throwable) {}
                @Override
                public void onComplete() {}
            });

            /** 执行命令 */
            long time = 0; //本次测试用例执行的时间
            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                statsCmd.close();
                time = stopWatch.getLastTaskTimeMillis();
            } catch (InterruptedException e) {
                System.out.println("执行命令异常");
                throw new RuntimeException(e);
            }

            /**
             * 由于内存监控是异步的，在for循环中可能将 maxMemory[0] 覆盖
             */
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            executeMessage.setOutputMessage(outputMessage[0]);
            executeMessage.setErrorOutputMessage(errorOutputMessage[0]);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);

        }

        //todo 内存获取还有问题
        System.out.println("=========================================");
        System.out.println("执行结果：" + executeMessageList);
        System.out.println("=========================================");

        /**5. 整理输出（输出结果、花费的时间、内存） */
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        //所有测试用例中花费的时间的最大值、内存的最大值
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorOutputMessage = executeMessage.getErrorOutputMessage();
            //程序执行过程有错误
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
            if(memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
        }
        // 所有测试用例都正常执行
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutput(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(maxMemory);
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        /**6. 文件清理（程序执行结束后可以把保存的用户代码文件删除），防止服务器空间不足 */
        if(FileUtil.exist(userCodeParentDir)) {
            boolean del = FileUtil.del(userCodeParentDir);
            System.out.println("用户代码文件删除" + (del ? "成功" : "失败"));
        }

        System.out.println("最终返回结果：" + executeCodeResponse);
        return executeCodeResponse;
    }

    /**
     * 6. 当程序抛出异常时进行处理
     * 程序执行过程中出现异常（编译错误、或执行过程中发生运行时异常）时的返回
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList());
        executeCodeResponse.setMessage(e.getMessage());
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
