package com.baimao.codesandbox.security;

import java.security.Permission;

/**
 * @author baimao
 * @title DefaultSecurityManager
 * 禁止所有的安全管理器
 */
public class BanAllSecurityManager extends SecurityManager {

    @Override
    public void checkPermission(Permission perm) {
//        super.checkPermission(perm);
        throw new SecurityException("权限不足，" + perm);
    }
}
