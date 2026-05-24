package com.baimao.codesandbox.core.docker.pool;

import cn.hutool.core.io.FileUtil;
import com.baimao.codesandbox.core.docker.DockerSandboxLanguage;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 负责创建、启动和销毁池化沙箱容器。
 */
@Slf4j
public class SandboxContainerFactory {

    private static final String USER_DIR = "user.dir";
    private static final String TMP_CODE = "tmpCode";
    public static final String CONTAINER_WORKSPACE = "/sandbox";

    private final DockerClient dockerClient;
    private final SandboxPoolProperties properties;

    public SandboxContainerFactory(DockerClient dockerClient, SandboxPoolProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    /**
     * 创建并启动一个长期驻留的语言容器，后续请求通过 docker exec 复用它。
     */
    public SandboxContainer create(DockerSandboxLanguage language) {
        String sandboxHostRoot = getSandboxHostRoot(language);
        if (!FileUtil.exist(sandboxHostRoot)) {
            FileUtil.mkdir(sandboxHostRoot);
        }

        HostConfig hostConfig = new HostConfig();
        // 容器内外数据路径映射
        hostConfig.setBinds(new Bind(sandboxHostRoot, new Volume(CONTAINER_WORKSPACE)));
        // 每个容器的最大使用内存 与 cpu核数
        hostConfig.withMemory(properties.getMemoryBytes());
        hostConfig.withCpuCount(properties.getCpuCount());

        // 配置容器
        CreateContainerResponse response = dockerClient.createContainerCmd(language.getDockerImage())
                .withNetworkDisabled(true)  // 禁用容器网络
                .withReadonlyRootfs(true)   // 把容器根文件系统设为只读
                .withHostConfig(hostConfig) // 应用资源和挂载配置
                // 容器启动后不直接执行用户代码，而是一直挂着不退出。后续每次请求通过 docker exec 在这个常驻容器里编译/运行代码。
                .withCmd("sh", "-c", "while true; do sleep 3600; done") // 死循环里睡眠，保持容器存活，且不空转 cpu
                .exec();

        String containerId = response.getId();
        // 创建容器
        dockerClient.startContainerCmd(containerId).exec();
        log.info("create sandbox container success, language={}, containerId={}", language.getLanguage(), containerId);
        return new SandboxContainer(containerId, language);
    }

    /**
     * 强制移除异常或需要轮换的容器。
     */
    public void remove(SandboxContainer container) {
        if (container == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(container.getContainerId()).withForce(true).exec();
            log.info("remove sandbox container success, containerId={}", container.getContainerId());
        } catch (Exception e) {
            log.warn("remove sandbox container failed, containerId={}", container.getContainerId(), e);
        }
    }

    public String getSandboxHostRoot(DockerSandboxLanguage language) {
        return System.getProperty(USER_DIR) + File.separator + TMP_CODE + language.getPrefix();
    }
}
