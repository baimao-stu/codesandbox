package com.baimao.codesandbox.core.docker.pool;

import com.baimao.codesandbox.core.docker.DockerSandboxLanguage;

/**
 * 池化容器的运行时元数据。
 */
public class SandboxContainer {

    private final String containerId;
    private final DockerSandboxLanguage language;
    private final long createTimeMillis;    // 容器的创建时间，达到最大存活时间则销毁
    private int useCount;   // 容器的执行次数，达到最大复用次数则销毁

    public SandboxContainer(String containerId, DockerSandboxLanguage language) {
        this.containerId = containerId;
        this.language = language;
        this.createTimeMillis = System.currentTimeMillis();
    }

    public String getContainerId() {
        return containerId;
    }

    public DockerSandboxLanguage getLanguage() {
        return language;
    }

    public long getCreateTimeMillis() {
        return createTimeMillis;
    }

    public int getUseCount() {
        return useCount;
    }

    public void increaseUseCount() {
        this.useCount++;
    }
}
