package com.baimao.codesandbox.core.old.utils;

import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.model.ExecuteMessage;
import org.springframework.util.StopWatch;

import java.io.*;

/**
 * @author baimao
 * @title ProcessUtil
 * 进程工具类
 */
public class ProcessUtil {

    /**
     * 执行进程并获取控制台返回结果
     * @param process 对应的进程
     * @param operation 本次进程执行的操作
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process process,String operation){
        ExecuteMessage executeMessage = ExecuteMessage.builder().build();
        try {
            /** 统计执行时间 */
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();

            /** 执行命令 */
            int waitValue = process.waitFor();
            executeMessage.setWaitValue(waitValue);

            stopWatch.stop();
            System.out.println("waitFor时间：" + stopWatch.getLastTaskTimeMillis());
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
            //执行正常
            if(waitValue == 0) {
                System.out.println(operation + "成功");
                //获取控制台返回给进程的结果
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                String compileOutputLine;
                while((compileOutputLine = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(compileOutputLine + "\n");
                }
                executeMessage.setOutputMessage(compileOutputStringBuilder.toString());
            }else {
                //执行异常
                System.out.println(operation + "失败，错误码：" + waitValue);
                //获取控制台返回给进程的结果
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder compileOutputStringBuilder = new StringBuilder();
                String compileOutputLine;
                while((compileOutputLine = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(compileOutputLine + "\n");
                }
                executeMessage.setOutputMessage(compileOutputStringBuilder.toString());

                //获取控制台返回给进程的异常信息
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errorCompileOutputStringBuilder = new StringBuilder();
                String errorCompileOutputLine;
                while((errorCompileOutputLine = errorBufferedReader.readLine()) != null) {
                    errorCompileOutputStringBuilder.append(errorCompileOutputLine + "\n");
                }
                executeMessage.setErrorOutputMessage(errorCompileOutputStringBuilder.toString());
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return executeMessage;
    }

    /**
     * 执行进程并获取控制台返回结果（与用户交互）
     * @param runProcess 对应的进程
     * @param operation 本次进程执行的操作
     * @param args 用户交互给的参数
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess,String operation,String args){
//        ExecuteMessage executeMessage = ExecuteMessage.builder().build();
//        try {
//            OutputStream outputStream = process.getOutputStream();
//            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
//            String[] s = args.split(" ");
//            // 向控制台发送参数
//            String params = StrUtil.join("\n", s) + "\n";
//            outputStreamWriter.write(params);
//            // 相当于回车，执行输入的发送
//            outputStreamWriter.flush();
//
//            /** 执行命令 */
//            int waitValue = process.waitFor();
//
//            //获取控制台返回给进程的结果
//            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//            StringBuilder compileOutputStringBuilder = new StringBuilder();
//            String compileOutputLine;
//            while((compileOutputLine = bufferedReader.readLine()) != null) {
//                compileOutputStringBuilder.append(compileOutputLine);
//            }
//            executeMessage.setOutputMessage(compileOutputStringBuilder.toString());
//            System.out.println(compileOutputStringBuilder);
//
//            outputStream.close();
//            outputStreamWriter.close();
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//        return executeMessage;
//    }
        ExecuteMessage executeMessage = ExecuteMessage.builder().build();

        try {
            // 向控制台输入程序
            OutputStream outputStream = runProcess.getOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            String[] s = args.split(" ");
            String join = StrUtil.join("\n", s) + "\n";
            outputStreamWriter.write(join);
            // 相当于按了回车，执行输入的发送
            outputStreamWriter.flush();

            // 分批获取进程的正常输出
            InputStream inputStream = runProcess.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder compileOutputStringBuilder = new StringBuilder();
            // 逐行读取
            String compileOutputLine;
            while ((compileOutputLine = bufferedReader.readLine()) != null) {
                compileOutputStringBuilder.append(compileOutputLine);
            }
            executeMessage.setOutputMessage(compileOutputStringBuilder.toString());
            // 记得资源的释放，否则会卡死
            outputStreamWriter.close();
            outputStream.close();
            inputStream.close();
            runProcess.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }

}
