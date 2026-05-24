package com.baimao.codesandbox.core.docker;

import com.baimao.codesandbox.core.docker.pool.SandboxContainerPoolManager;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerCodesandboxTemplateTest {

    @TempDir
    Path tempDir;

    @Test
    void saveCodeFileKeepsSubmittedSourceUnchanged() throws Exception {
        TestDockerCodesandbox sandbox = new TestDockerCodesandbox();
        String code = "class Main { String text = \"`n\"; }";
        String userCodeParentDir = sandbox.saveCodeFile(code);

        try {
            String savedCode = new String(Files.readAllBytes(new File(userCodeParentDir, "Main.java").toPath()),
                    StandardCharsets.UTF_8);

            assertEquals(code, savedCode);
        } finally {
            sandbox.deleteFile(userCodeParentDir);
        }
    }

    @Test
    void buildCaseMessageTreatsNonZeroExitCodeAsRuntimeError() throws Exception {
        TestDockerCodesandbox sandbox = new TestDockerCodesandbox();
        writeCaseFile(0, "out", "");
        writeCaseFile(0, "err", "");
        writeCaseFile(0, "time", "real 0.01\nuser 0.00\nsys 0.00\n");
        writeCaseFile(0, "exit", "1");

        ExecuteMessage executeMessage = invokeBuildCaseMessage(sandbox, 0);

        assertEquals(1, executeMessage.getWaitValue());
        assertEquals("Process exited with code 1", executeMessage.getErrorOutputMessage());
    }

    @Test
    void buildCaseMessagePreservesWhitespaceOnlyStdout() throws Exception {
        TestDockerCodesandbox sandbox = new TestDockerCodesandbox();
        writeCaseFile(0, "out", " \n");
        writeCaseFile(0, "err", "");
        writeCaseFile(0, "time", "real 0.01\nuser 0.00\nsys 0.00\n");
        writeCaseFile(0, "exit", "0");

        ExecuteMessage executeMessage = invokeBuildCaseMessage(sandbox, 0);

        assertEquals(" \n", executeMessage.getOutputMessage());
    }

    private ExecuteMessage invokeBuildCaseMessage(TestDockerCodesandbox sandbox, int caseIndex) throws Exception {
        Method method = DockerCodesandboxTemplate.class.getDeclaredMethod(
                "buildCaseMessage", String.class, int.class, long.class);
        method.setAccessible(true);
        return (ExecuteMessage) method.invoke(sandbox, tempDir.toString(), caseIndex, 1L);
    }

    private void writeCaseFile(int caseIndex, String suffix, String content) throws Exception {
        // 模拟批量 runner 生成的单用例结果文件，避免单元测试依赖 Docker 环境。
        Files.write(tempDir.resolve("case-" + caseIndex + "." + suffix),
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static class TestDockerCodesandbox extends DockerCodesandboxTemplate {

        TestDockerCodesandbox() {
            super(DockerSandboxLanguage.JAVA, (SandboxContainerPoolManager) null);
            super.timeOut = 10000L;
        }

        @Override
        protected ExecCreateCmdResponse createCompileCmd(String containerId, String containerCodeDir) {
            return null;
        }

        @Override
        protected String[] createExecCmd(String containerCodeDir) {
            return new String[]{"true"};
        }
    }
}
