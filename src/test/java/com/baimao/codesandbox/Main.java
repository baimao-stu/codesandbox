package com.baimao.codesandbox;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @author baimao
 * @title Main
 */
public class Main {
    public static void main(String[] args) {

        List<String> t = new ArrayList<>();
        t.add("1");
        t.add("3");
        String join = StringUtils.join(t, "\n");
        System.out.println(join);

    }
}
