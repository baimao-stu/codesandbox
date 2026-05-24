package com.baimao.codesandbox.core.docker.pool;

import com.baimao.codesandbox.core.docker.DockerSandboxLanguage;
import com.baimao.codesandbox.docker.DockerClientFactory;
import com.github.dockerjava.api.DockerClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 管理所有语言的容器池，服务启动时统一预热。
 */
@Component
public class SandboxContainerPoolManager implements InitializingBean, DisposableBean {

    private final SandboxPoolProperties properties;
    private final DockerClient dockerClient;

    private final Map<DockerSandboxLanguage, SandboxContainerPool> poolMap =
            new EnumMap<>(DockerSandboxLanguage.class);

    public SandboxContainerPoolManager(SandboxPoolProperties properties) {
        this.properties = properties;
        this.dockerClient = DockerClientFactory.createDockerClient();
    }

    /**
     * Bean 初始化（依赖注入）完成后执行 ———— 初始化容器池并预热容器
     */
    @Override
    public void afterPropertiesSet() {
        register(DockerSandboxLanguage.JAVA, properties.getJavaSize());
        register(DockerSandboxLanguage.CPP, properties.getCppSize());
        register(DockerSandboxLanguage.PYTHON, properties.getPythonSize());
    }

    public SandboxContainerPool getPool(DockerSandboxLanguage language) {
        SandboxContainerPool pool = poolMap.get(language);
        if (pool == null) {
            throw new RuntimeException("Unsupported language");
        }
        return pool;
    }

    /**
     * Bean 销毁前执行 ———— 关闭容器池并删除空闲容器
     * 当 Spring 容器正常关闭时，会调用 destroy()
     */
    @Override
    public void destroy() {
        for (SandboxContainerPool pool : poolMap.values()) {
            pool.shutdown();
        }
    }

    private void register(DockerSandboxLanguage language, int size) {
        SandboxContainerFactory containerFactory = new SandboxContainerFactory(dockerClient, properties);
        SandboxContainerPool pool = new SandboxContainerPool(language, size, properties, containerFactory);
        pool.preheatAsync();
        poolMap.put(language, pool);
    }
}
