package com.baimao.codesandbox.core.old;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.CodeSandbox;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import com.baimao.codesandbox.model.ExecuteMessage;
import com.baimao.codesandbox.model.JudgeInfo;
import com.baimao.codesandbox.core.old.utils.ProcessUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author baimao
 * @title JavaNativeCodeSandbox
 */
public class JavaNativeCodeSandboxOld implements CodeSandbox {

    private final static String USER_DIR = "user.dir";

    private final static String TMP_CODE = "tmpCode";

    private final static String CLASS_NAME = "Main.java";

    private static final String SECURITY_MANAGER_PATH = "D:\\Project\\OJ\\codesandbox\\src\\main\\resources\\security";

    private static final String SECURITY_MANAGER_CLASS_NAME = "MySecurityManager";

    private static final Long TIME_OUT = 2000L;

    public static void main(String[] args) {
        JavaNativeCodeSandboxOld javaNativeCodeSandboxOld = new JavaNativeCodeSandboxOld();
//        String filePath = "simpleComputeArgs" + File.separator + CLASS_NAME;
//        String filePath = "simpleComputeInteract" + File.separator + CLASS_NAME;
//        String filePath = "unsafeTime" + File.separator + CLASS_NAME;
//        String filePath = "unsafeMemory" + File.separator + CLASS_NAME;
        String filePath = "unsafeWrite" + File.separator + CLASS_NAME;
        String code = ResourceUtil.readStr(filePath, StandardCharsets.UTF_8);
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setCode(code);
        executeCodeRequest.setInput(Arrays.asList("1 2","3 4"));
        executeCodeRequest.setLanguage("java");
        javaNativeCodeSandboxOld.executeCode(executeCodeRequest);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new BanAllSecurityManager());

        String code = executeCodeRequest.getCode();
        List<String> inputList = executeCodeRequest.getInput();

        /**1. 将用户代码保存为文件，文件名为Main.java，文件里的主类务必为 Main */
        // 存放用户代码的目录
        String tmpCodeFile = System.getProperty(USER_DIR) + File.separator + TMP_CODE;
        if(!FileUtil.exist(tmpCodeFile)) {
            FileUtil.mkdir(tmpCodeFile);
        }
        // 由于每个文件都命名为 Main.java，因此为每个文件都新建一个目录
        String userCodeParentDir = tmpCodeFile + File.separator + UUID.randomUUID();
        String userCodeFile = userCodeParentDir + File.separator + CLASS_NAME;

        File file = FileUtil.writeString(code, userCodeFile, StandardCharsets.UTF_8);

        /**2. 编译代码 */
        //控制台要执行的命令（编译）
        String compileCmd = String.format("javac -encoding utf-8 %s",file.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtil.runProcessAndGetMessage(compileProcess, "编译");
            System.out.println(executeMessage);
        } catch (IOException e) {
            return getErrorResponse(e);
        }

        /**3. 执行代码，得到输出结果 */
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for(String inputArgs: inputList) {
            //控制台要执行的命令（运行）,限制最大堆内存 256M
//            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s Main %s",userCodeParentDir,inputArgs);
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=utf-8 -cp %s;%s -Djava.security.manager=%s Main %s",userCodeParentDir,SECURITY_MANAGER_PATH,SECURITY_MANAGER_CLASS_NAME,inputArgs);
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 限制5s运行时间
                new Thread(() -> {
                    try {
                        Thread.sleep(5000l);
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                /** 与用户交互，接收输入的参数 */
//                String params = new Scanner(System.in).nextLine();
//                ExecuteMessage executeMessage = ProcessUtil.runInteractProcessAndGetMessage(runProcess, "运行",params);
                /** 控制台获取参数 */
                ExecuteMessage executeMessage = ProcessUtil.runProcessAndGetMessage(runProcess, "运行");
                executeMessageList.add(executeMessage);
                System.out.println(executeMessage);
            } catch (IOException e) {
                return getErrorResponse(e);
            }
        }

        /**4. 整理输出（输出结果、花费的时间、内存） */
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;   //所有测试用例中花费的时间的最大值
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorOutputMessage = executeMessage.getErrorOutputMessage();
            //程序执行过程有错误
            if(StrUtil.isNotBlank(errorOutputMessage)) {
                executeCodeResponse.setMessage(errorOutputMessage);
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getOutputMessage());
            Long time = executeMessage.getTime();
            if(time != null) {
                maxTime = Math.max(maxTime,time);
            }
         }
        // 所有测试用例都正常执行
        if(outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutput(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
//        judgeInfo.setMemory(1l);
        judgeInfo.setTime(maxTime);

        executeCodeResponse.setJudgeInfo(judgeInfo);

        /**5. 文件清理（程序执行结束后可以把保存的用户代码文件删除），防止服务器空间不足 */
        if(FileUtil.exist(userCodeParentDir)) {
            boolean del = FileUtil.del(userCodeParentDir);
            System.out.println("用户代码文件删除" + (del ? "成功" : "失败"));
        }

        return executeCodeResponse;
    }

    /**
     * 6. 当程序抛出异常时进行处理
     * 程序执行过程中出现异常（编译错误、或执行过程中发生运行时异常）时的返回
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutput(new ArrayList());
        executeCodeResponse.setMessage(e.getMessage());
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
