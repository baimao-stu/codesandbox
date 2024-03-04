package com.baimao.codesandbox.model;

import lombok.Builder;
import lombok.Data;

/**
 * @author baimao
 * @title ExecuteMessage
 * （命令行）执行代码后返回的结果（每个用例之后得到的信息包括输出、本次执行所用时间、内存）
 */
@Data
@Builder
public class ExecuteMessage {

    private Integer waitValue;

    /**
     * 执行正常时控制台的输出结果
     */
    private String outputMessage;

    /**
     * 执行错误时控制台的输出信息
     */
    private String errorOutputMessage;

    /**
     * 程序的执行时间
     */
    private Long time;

    /**
     * 程序的内存使用
     */
    private Long memory;

}
