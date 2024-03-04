package com.baimao.codesandbox.security;

import cn.hutool.core.io.FileUtil;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.List;

/**
 * @author baimao
 * @title TestMySecurityManager
 */
public class TestMySecurityManager {

    public static void main(String[] args) {
        System.setSecurityManager(new MySecurityManager());
//        System.setSecurityManager(new DefaultSecurityManager());
//        System.setSecurityManager(new BanAllSecurityManager());
//        List<String> strings = FileUtil.readLines("D:\\Project\\OJ\\codesandbox\\src\\main\\resources\\application.yml", Charset.defaultCharset());
//        System.out.println(strings);
        FileUtil.writeString("asda","asd",Charset.defaultCharset());
        System.out.println("--------测试Java安全管理器-----------");

    }
}
