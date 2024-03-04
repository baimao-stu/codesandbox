package com.baimao.codesandbox.controller;

import com.baimao.codesandbox.core.CodeSandboxFactory;
import com.baimao.codesandbox.core.CodesandboxTemplate;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author baimao
 * @title MainController
 */
@RestController
public class MainController {

    //接口调用鉴权请求头和密钥
    private static final String AUTH_REQUEST_HEADER = "auth";
    private static final String AUTH_REQUEST_SECRET = "secret"; //最好加密

//    @Resource
//    private JavaNativeCodeSandbox javaNativeCodeSandbox;
//
//    @Resource
//    private JavaDockerCodeSandbox javaDockerCodeSandbox;

    @PostMapping("/executeCode")
    ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        //验证密钥
        String secret = request.getHeader(AUTH_REQUEST_HEADER);
        if(! AUTH_REQUEST_SECRET.equals(secret)) {
            response.setStatus(403);
            return null;
        }
        if(executeCodeRequest == null) {
            throw new RuntimeException("请求参数为空");
        }
//        return javaNativeCodeSandbox.executeCode(executeCodeRequest);
//        return javaDockerCodeSandbox.executeCode(executeCodeRequest);

        String language = executeCodeRequest.getLanguage();
        CodesandboxTemplate codesandboxTemplate = CodeSandboxFactory.getInstance(language);
        return codesandboxTemplate.executeCode(executeCodeRequest);
    }

}
