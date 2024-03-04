package com.baimao.codesandbox.security;

import java.security.Permission;

/**
 * @author baimao
 * @title DefaultSecurityManager
 */
public class DefaultSecurityManager extends SecurityManager {

    /**
     * super.checkPermission(perm);默认禁止所有权限
     * @param perm
     */
    @Override
    public void checkPermission(Permission perm) {
        super.checkPermission(perm);
        System.out.println("默认不做任何限制");
        System.out.println(perm);
    }
}
