package com.baimao.codesandbox.core.docker.pool;

/**
 * 容器池在限定时间内没有可用容器时抛出该异常。
 */
public class SandboxBusyException extends RuntimeException {

    public SandboxBusyException(String message) {
        super(message);
    }
}
