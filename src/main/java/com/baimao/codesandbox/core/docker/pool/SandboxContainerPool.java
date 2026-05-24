package com.baimao.codesandbox.core.docker.pool;

import com.baimao.codesandbox.core.docker.DockerSandboxLanguage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单语言固定大小容器池，一个容器同一时间只服务一个请求。
 */
@Slf4j
public class SandboxContainerPool {

    private final DockerSandboxLanguage language;
    private final int poolSize;
    private final SandboxPoolProperties properties;
    private final SandboxContainerFactory containerFactory;

    private final BlockingQueue<SandboxContainer> idleContainers;
    private final ExecutorService replenishExecutor;
    private final AtomicInteger totalContainers = new AtomicInteger(0);

    /**
     * 初始化指定语言的固定容量容器池，并创建后台补充容器的单线程执行器。
     */
    public SandboxContainerPool(DockerSandboxLanguage language,
                                int poolSize,
                                SandboxPoolProperties properties,
                                SandboxContainerFactory containerFactory) {
        this.language = language;
        this.poolSize = Math.max(1, poolSize);
        this.properties = properties;
        this.containerFactory = containerFactory;
        this.idleContainers = new ArrayBlockingQueue<>(this.poolSize);
        /**
         * 异步补充/预热容器
         * 为什么用 newSingleThreadExecutor？
         *
         * 每种语言池有自己的补充线程；
         * 同一种语言的容器创建任务串行执行，避免同时大量创建 Docker 容器；
         * 创建容器可能比较慢，用后台线程不阻塞启动主流程或请求主流程；
         * 线程名带语言，比如 sandbox-pool-replenish-java，日志里好定位问题；
         * setDaemon(true) 表示它是守护线程，不会阻止 JVM 退出。
         */
        this.replenishExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("sandbox-pool-replenish-" + language.getLanguage());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 后台预热固定数量的容器，预热失败不会阻断 Spring 应用启动。
     */
    public void preheatAsync() {
        for (int i = 0; i < poolSize; i++) {
            replenishAsync();
        }
        log.info("submit sandbox pool preheat tasks, language={}, size={}", language.getLanguage(), poolSize);
    }

    /**
     * 从池中借出容器，池为空时等待一小段时间，避免请求无限阻塞。
     */
    public SandboxContainer acquire() {
        /**
         * 服务刚启动、预热还没完成时，请求也可以同步创建容器；
         * 池里因为销毁旧容器产生空位时，请求可以快速补一个。
         */
        SandboxContainer createdContainer = createContainerIfAbsent();

        if (createdContainer != null) {
            return createdContainer;
        }
        try {
            while (true) {
                SandboxContainer container = idleContainers.poll(properties.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
                if (container == null) {
                    throw new SandboxBusyException("Sandbox container pool is busy");
                }
                if (shouldRetire(container)) {
                    removeContainer(container);
                    replenishAsync();
                    continue;
                }
                return container;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxBusyException("Acquire sandbox container interrupted");
        }
    }

    /**
     * 正常执行完成后归还容器，超过复用阈值则轮换重建。
     */
    public void release(SandboxContainer container) {
        if (container == null) {
            return;
        }
        container.increaseUseCount();
        if (shouldRetire(container)) {
            removeContainer(container);
            replenishAsync();
            return;
        }
        // 容器放回池里
        if (!idleContainers.offer(container)) {
            removeContainer(container);
            replenishAsync();
        }
    }

    /**
     * 异常、超时或疑似污染的容器不再复用，直接销毁并补充新容器。
     */
    public void invalidate(SandboxContainer container) {
        removeContainer(container);
        replenishAsync();
    }

    /**
     * 服务关闭时销毁池内空闲容器。
     */
    public void shutdown() {
        replenishExecutor.shutdownNow();
        List<SandboxContainer> containers = new ArrayList<>();

        /**
         * 把 idleContainers 队列里的所有空闲容器一次性转移到 containers 列表里。
         * 转移后，空闲队列基本就被清空了。 ———— 被借出去的容器不在队列里，无法销毁
         */
        idleContainers.drainTo(containers);

        for (SandboxContainer container : containers) {
            removeContainer(container);
        }
    }

    /**
     * 判断容器是否达到复用次数或存活时间上限，需要从池中淘汰。
     */
    private boolean shouldRetire(SandboxContainer container) {
        long aliveTime = System.currentTimeMillis() - container.getCreateTimeMillis();
        return container.getUseCount() >= properties.getMaxUseCount()
                || aliveTime >= properties.getMaxAliveMs();
    }

    /**
     * 异步补充一个空闲容器，创建成功后放回空闲队列。
     */
    private void replenishAsync() {
        replenishExecutor.submit(() -> {
            try {
                if (!reserveContainerSlot()) {
                    return;
                }
                SandboxContainer container = createReservedContainer();
                if (!idleContainers.offer(container)) {
                    removeContainer(container);
                }
            } catch (Throwable e) {
                log.error("replenish sandbox container failed, language={}", language.getLanguage(), e);
            }
        });
    }

    /**
     * 当池容量还未占满时同步创建容器，用于请求借出时快速补齐空位。
     */
    private SandboxContainer createContainerIfAbsent() {
        if (!reserveContainerSlot()) {
            return null;
        }
        try {
            return createReservedContainer();
        } catch (Throwable e) {
            log.error("create sandbox container failed, language={}", language.getLanguage(), e);
            throw new SandboxBusyException("Sandbox container create failed");
        }
    }

    /**
     * 在已预留容量槽位的前提下创建容器，失败时回滚容器计数。
     */
    private SandboxContainer createReservedContainer() {
        try {
            return containerFactory.create(language);
        } catch (Throwable e) {
            totalContainers.decrementAndGet();
            throw e;
        }
    }

    /**
     * 通过 CAS 预留一个容器容量槽位，避免 并发创建 超过池大小的容器。
     * 在真正创建 Docker 容器之前，先抢占一个“容器名额”，确保并发情况下容器总数不会超过池大小。
     */
    private boolean reserveContainerSlot() {
        while (true) {
            int current = totalContainers.get();
            if (current >= poolSize) {
                return false;
            }
            // CAS 原子操作，如果 totalContainers 当前值仍然等于刚才读到的 current，就把它加 1。
            if (totalContainers.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 从 Docker 中移除容器，并同步扣减池内容器总数。
     */
    private void removeContainer(SandboxContainer container) {
        containerFactory.remove(container);
        totalContainers.decrementAndGet();
    }
}
