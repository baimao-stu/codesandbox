package com.baimao.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱需要执行的命令（1.编译 2.运行）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeSandboxCmd {
    private String compileCmd;
    private String runCmd;
}
