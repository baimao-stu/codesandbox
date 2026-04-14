package com.baimao.codesandbox.controller;

import com.baimao.codesandbox.core.CodeSandboxFactory;
import com.baimao.codesandbox.core.CodesandboxTemplate;
import com.baimao.codesandbox.core.docker.DockerCodeSandboxFactory;
import com.baimao.codesandbox.core.docker.DockerCodesandboxTemplate;
import com.baimao.codesandbox.model.ExecuteCodeRequest;
import com.baimao.codesandbox.model.ExecuteCodeResponse;
import org.springframework.web.bind.annotation.GetMapping;
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
    private static final String AUTH_REQUEST_SECRET = "secret";
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

        String language = executeCodeRequest.getLanguage();
//        CodesandboxTemplate codesandboxTemplate = CodeSandboxFactory.getInstance(language);
        DockerCodesandboxTemplate codesandboxTemplate = DockerCodeSandboxFactory.getInstance(language);
        return codesandboxTemplate.executeCode(executeCodeRequest);
    }

    @GetMapping("/healthy")
    public String healthy() {
        return "healthy";
    }

}
