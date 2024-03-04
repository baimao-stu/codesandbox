package com.baimao.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @author baimao
 * @title ExecuteCodeRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {

    //一组输入用例
    private List<String> input;

    //要执行的代码
    private String code;

    //编程语言
    private String language;
}
