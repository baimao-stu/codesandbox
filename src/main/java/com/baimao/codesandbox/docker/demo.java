package com.baimao.codesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;

import java.util.List;

public class demo {

    public static void main(String[] args) throws InterruptedException {
        //docker在本机
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        //拉取镜像
        String image = "nginx:latest";
//        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
//        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
//            @Override
//            public void onNext(PullResponseItem item) {
//                System.out.println("下载镜像：" + item.getStatus());
//                super.onNext(item);
//            }
//        };
//        pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
//        System.out.println("下载完成");

        //创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse exec = containerCmd
                .withCmd("echo","hello-world!!!!")  //启动容器时顺带执行的命令
                .exec();
//        System.out.println(exec);
        String containerId = exec.getId();
        System.out.println("容器id：" + containerId);

        //查看容器
        ListContainersCmd listContainersCmd = dockerClient.listContainersCmd();
        List<Container> containerList = listContainersCmd.withShowAll(true).exec();
        for (Container container : containerList) {
            System.out.println(container);
        }

        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

        //查看容器日志
        LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback(){
            @Override
            public void onNext(Frame item) {
                System.out.println("日志：" + new String(item.getPayload()));
                super.onNext(item);
            }
        };
        dockerClient.logContainerCmd(containerId)
                .withStdErr(true)
                .withStdOut(true)
                .exec(logContainerResultCallback)
                .awaitCompletion(); //阻塞等待日志输出

        //删除容器
        dockerClient.removeContainerCmd(containerId).withForce(true).exec();

        //删除镜像
//        dockerClient.removeImageCmd(image).exec();

    }
}
