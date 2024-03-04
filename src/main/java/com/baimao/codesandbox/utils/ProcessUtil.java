package com.baimao.codesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.baimao.codesandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.*;

/**
 * 进程处理工具类
 */
@Slf4j
public class ProcessUtil {

    /**
     * 运行进行
     *
     * @param runProcess    运行进程
     * @param operationName 操作名称
     * @return {@link ExecuteMessage}
     */
    public static ExecuteMessage handleProcessMessage(Process runProcess, String operationName) {
        int exitCode;
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        try {
            log.info(operationName + "成功");
//            log.error(operationName + "失败，错误码为: {}", exitCode);
            BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream()));
            String errorRunOutputLine;
            while ((errorRunOutputLine = errorBufferedReader.readLine()) != null) {
                errorOutput.append(errorRunOutputLine);
            }
            log.error("错误输出为：{}", errorOutput);

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));
            String runOutputLine;
            while ((runOutputLine = bufferedReader.readLine()) != null) {
                /**去除尾部的空格*/
                String trimOutputLine = runOutputLine.replaceAll("\\s+$", "");
                /**程序每输出一行，补上一个换行符*/
                output.append(trimOutputLine + "\n");
            }
            if (StrUtil.isNotBlank(output)) {
                log.info("正常输出结果：{}", output);
            }
            exitCode = runProcess.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return ExecuteMessage.builder()
                .waitValue(exitCode)
                .outputMessage(output.toString())
                .errorOutputMessage(errorOutput.toString())
                .build();
    }


    /**
     * 将测试用例往Process的输出流写，再从Process的输入流获取结果
     * @param runProcess
     * @param input
     * @param operationName
     * @return
     */
    public static ExecuteMessage handleProcessInteraction(Process runProcess, String input, String operationName) {
        OutputStream outputStream = runProcess.getOutputStream();
//        OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
        try {
//            outputStreamWriter.write((input + "\n"));
//            outputStreamWriter.flush();
//            outputStreamWriter.close();
            outputStream.write((input + "\n").getBytes());
            outputStream.flush();
            outputStream.close();
            ExecuteMessage executeMessage = handleProcessMessage(runProcess, operationName);
            return executeMessage;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                log.error("关闭输入流失败");
            }
        }
    }

}
