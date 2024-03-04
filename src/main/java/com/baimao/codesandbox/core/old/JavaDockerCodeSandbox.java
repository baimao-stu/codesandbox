package com.baimao.codesandbox.core.old;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author baimao
 * @title JavaNativeCodeSandbox
 */
//@Component
public class JavaDockerCodeSandbox extends JavaCodesandboxTemplate {

    private static Boolean FIRST_PULL_IMAGE = true;      //是否是初次拉取镜像

    private static final Long TIME_OUT = 2000L; //容器超时时间

    /**
     * 创建容器，执行代码，返回输出结果
     * @param codeFile 原代码文件
     * @param inputList
     * @return
     */
    @Override
    public List<ExecuteMessage> runFile(File codeFile, List<String> inputList) {
        String userCodeParentDir = codeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        /** 1. 拉取Docker镜像 */
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
         *  2. 创建容器：
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

        /**3. 启动容器，执行代码，获取输出结果 */
        dockerClient.startContainerCmd(containId).exec();
        // 要执行的命令eg：docker exec 64a969d5de10 java -cp /app Main 1 2
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        StopWatch stopWatch = new StopWatch();
        // 测试多个输入用例
        for (String inputArgs : inputList) {
            String[] inputArgsArray = inputArgs.split(" ");
//            String[] cmdArray = ArrayUtil.append(new String[]{"java","-cp","/app","Main"},inputArgsArray);
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
            StringBuilder outputMessageBuilder = new StringBuilder();
            StringBuilder errorOutputMessageBuilder = new StringBuilder();
            final long[] maxMemory = {0};   //本次测试用例执行过程中检测到的最大内存占用
            final boolean[] timeout = {true}; //是否超时，默认true
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback(){
                String outputMessage = null;
                String errorOutputMessage = null;
                @Override
                public void onNext(Frame frame) {
//                    if(ObjUtil.isNotEmpty(outputMessage[0]) || ObjUtil.isNotEmpty(errorOutputMessage[0])) return;

                    StreamType streamType = frame.getStreamType();
                    if(StreamType.STDERR.equals(streamType)) {
                        errorOutputMessage = new String(frame.getPayload());
                        errorOutputMessageBuilder.append(errorOutputMessage);
                    }else {
                        outputMessage = new String(frame.getPayload());
                        outputMessageBuilder.append(outputMessage);
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
//                    System.out.println("要执行的命令：" + StrUtil.join(" ",cmdArray));
                    Long memoryUsage = statistics.getMemoryStats().getUsage();
//                    System.out.println("内存占用：" + memoryUsage);
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
                        .awaitCompletion();
                stopWatch.stop();
                statsCmd.close();
                time = stopWatch.getLastTaskTimeMillis();
            } catch (InterruptedException e) {
                System.out.println("执行命令异常");
                throw new RuntimeException(e);
            }

            /**
             * 由于内存监控是异步的（没有awaitCompletion()方法），在for循环中可能将 maxMemory[0] 覆盖
             */
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
            executeMessage.setOutputMessage(outputMessageBuilder.toString());
            executeMessage.setErrorOutputMessage(errorOutputMessageBuilder.toString());
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }

        //todo 内存获取还有问题（每个用例的内存还分开算还是统一算最大就好？）
        System.out.println("=========================================");
        System.out.println("执行结果：" + executeMessageList);
        System.out.println("=========================================");

        return executeMessageList;
    }
}
