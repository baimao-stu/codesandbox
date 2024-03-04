package com.baimao.codesandbox.model;

import lombok.Data;

/**
 * 判题信息（用户提交题目后的状态）
 */
@Data
public class JudgeInfo {

    /**
     * 判题状态
     */
    private String message;

    /**
     * 用户提交的内存大小
     */
    private Long memory;

    /**
     * 用户的代码花费的时间
     */
    private Long time;

    /**
     * 执行哪个测试用例时出错（使程序出错或输出答案不对）
     */
    private Integer errorIndex;

}
