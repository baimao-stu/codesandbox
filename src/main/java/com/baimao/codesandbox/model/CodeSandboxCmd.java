package com.baimao.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 沙箱需要执行的命令（1.编译 2.运行）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeSandboxCmd {
    private List<String> compileCmd;
    private List<String> runCmd;
}
