package com.baimao.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author baimao
 * @title ExecuteCodeResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponse {

    //一组输出用例
    private  List<String> output;

    //接口信息（eg. 调用代码沙箱接口有没有出错）
    private String message;

    /**
     * 执行状态
     * 测试用例全通过：1
     * 代码编译错误：2
     * 程序执行过程出错：3
     */
    private Integer status;

    //判题信息(花费的内存，时间)
    private JudgeInfo judgeInfo;
}
