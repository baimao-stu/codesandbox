package com.baimao.codesandbox.core.old;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.CodeSandbox;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.baimao.codesandbox.core.old.utils.ProcessUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

/**
 * Java 代码沙箱模板方法
 */
@Slf4j
public abstract class JavaCodesandboxTemplate implements CodeSandbox {


    private final static String USER_DIR = "user.dir";

    private final static String TMP_CODE = "tmpCode";

    private final static String CLASS_NAME = "Main.java";

    private static final Long TIME_OUT = 10000L; //容器执行不得超过此事件


    /**
     * 执行流程
     * @param executeCodeRequest
     * @return
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();

        /**1. 将用户代码保存为文件，文件名为Main.java，文件里的主类务必为 Main */
        File codeFile = saveCodeFile(code);

        /**2. 编译代码 */
        ExecuteMessage executeCompileMessage = null;
        ExecuteCodeResponse outputResponse = new ExecuteCodeResponse();
        try {
            executeCompileMessage = compileCodeFile(codeFile);
            if(executeCompileMessage.getWaitValue() != 0) {
                outputResponse = getErrorResponse();
                return outputResponse;
            }
        } catch (IOException e) {
            outputResponse = getErrorResponse();
            return outputResponse;
        }
        System.out.println("编译信息：" + executeCompileMessage);

        /**3. 执行代码，得到输出结果 */
        List<ExecuteMessage> executeMessageList = runFile(codeFile, inputList);

        /**4. 整理输出（输出结果、花费的时间、内存） */
        outputResponse = getOutputResponse(executeMessageList);

        /**5. 文件清理（程序执行结束后可以把保存的用户代码文件删除），防止服务器空间不足 */
        boolean b = deleteFile(codeFile);
        if(!b) {
            log.error("deleteFile error, userCodeFile Path = {}",codeFile.getAbsolutePath());
        }
        return outputResponse;
    }

    /**
     * 1. 将用户代码保存为文件
     * @param code
     * @return
     */
    public File saveCodeFile(String code) {
        // 存放用户代码的目录
        String tmpCodeFile = System.getProperty(USER_DIR) + File.separator + TMP_CODE;
        if (!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        // 由于每个文件都命名为 Main.java，因此为每个文件都新建一个目录
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + CLASS_NAME;

        File file = FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 2. 编译代码
     * @param file
     * @return
     */
    public ExecuteMessage compileCodeFile(File file) throws IOException {
        //控制台要执行的命令（编译）
        String compileCmd = String.format("javac -encoding utf-8 %s", file.getAbsolutePath());
        Process compileProcess = Runtime.getRuntime().exec(compileCmd);
        ExecuteMessage executeMessage = ProcessUtil.runProcessAndGetMessage(compileProcess, "编译");
        return executeMessage;
    }

    /**
     * 3. 执行代码，得到输出结果
     * @param codeFile 原代码文件
     * @param inputList
     * @return
     */
    public List<ExecuteMessage> runFile(File codeFile,List<String> inputList) {
        String userCodeParentDir = codeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();

        for (String inputArgs : inputList) {
            //控制台要执行的命令（运行）,限制最大堆内存 256M
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s Main %s",userCodeParentDir,inputArgs);
//            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s;%s -Djava.security.manager=%s Main %s", userCodeParentDir, SECURITY_MANAGER_PATH, SECURITY_MANAGER_CLASS_NAME, inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 限制5s运行时间
//                new Thread(() -> {
//                    try {
//                        Thread.sleep(TIME_OUT);
//                        System.out.println("时间到，终止Process");
//                        if (runProcess.isAlive()) {
//                            runProcess.destroy();
//                        }
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                }).start();
                /** 与用户交互，接收输入的参数 */
                ExecuteMessage executeMessage = ProcessUtil.runInteractProcessAndGetMessage(runProcess, "运行",inputArgs);
                /** 控制台获取参数 */
//                ExecuteMessage executeMessage = ProcessUtil.runProcessAndGetMessage(runProcess, "运行");
//                executeMessage.setMemory(0L);   //本地代码沙箱不方便获取内存占用，默认为0
                executeMessageList.add(executeMessage);
                System.out.println(executeMessage);
            } catch (IOException e) {
                throw new RuntimeException("执行错误",e);
            }
        }
        return executeMessageList;
    }

    /**
     * 4. 整理输出结果返回
     * @param executeMessageList
     * @return
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        /** 所有测试用例中花费的时间的最大值、内存的最大值 */
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorOutputMessage = executeMessage.getErrorOutputMessage();
            //程序执行过程有错误
            if (StrUtil.isNotBlank(errorOutputMessage)) {
                executeCodeResponse.setMessage(errorOutputMessage);
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getOutputMessage());
            Long time = executeMessage.getTime();
            Long memory = executeMessage.getMemory();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            if(memory != null) {
                maxMemory = Math.max(maxMemory, memory);
            }
        }
        /** 程序执行所有测试用例的过程都正常 */
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutput(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(maxMemory);
        judgeInfo.setTime(maxTime);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;
    }

    /**
     * 5. 删除用户的代码文件（包括临时目录和文件）
     * @param codeFile
     * @return
     */
    public boolean deleteFile(File codeFile){
        String userCodeParentDir = codeFile.getParentFile().getAbsolutePath();
        if (FileUtil.exist(userCodeParentDir)) {
            boolean del = FileUtil.del(userCodeParentDir);
            System.out.println("用户代码文件删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;
    }

    /**
     * 6. 编译错误时进行处理
     * @return
     */
    private ExecuteCodeResponse getErrorResponse() {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList());
        executeCodeResponse.setMessage("编译错误");
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }

}
