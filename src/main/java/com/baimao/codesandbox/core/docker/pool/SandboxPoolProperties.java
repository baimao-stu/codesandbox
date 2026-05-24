package com.baimao.codesandbox.core.docker.pool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 容器池配置，默认值用于本地开发，生产环境可通过 application.yml 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sandbox.pool")
public class SandboxPoolProperties {

    /**
     * Java 语言预热容器数量。
     */
    private int javaSize = 2;

    /**
     * C++ 语言预热容器数量。
     */
    private int cppSize = 2;

    /**
     * Python 语言预热容器数量。
     */
    private int pythonSize = 2;

    /**
     * 从池中获取容器的最长等待时间。
     */
    private long acquireTimeoutMs = 3000L;

    /**
     * 单个容器最多复用次数，超过后会轮换重建。
     */
    private int maxUseCount = 50;

    /**
     * 单个容器最长存活时间，超过后会轮换重建。
     */
    private long maxAliveMs = 30 * 60 * 1000L;

    /**
     * 单个容器内存限制，单位字节。
     */
    private long memoryBytes = 256 * 1000 * 1000L;

    /**
     * 单个容器 CPU 核数限制。
     */
    private long cpuCount = 1L;
}
