import java.security.Permission;

/**
 * @author baimao
 * @title MySecurityManager
 */
public class MySecurityManager extends SecurityManager {

    @Override
    public void checkPermission(Permission perm) {
//        super.checkPermission(perm);
    }

    @Override
    public void checkRead(String file) {
        super.checkRead(file);
//        throw new SecurityException("checkRead：" + file);
    }

    @Override
    public void checkWrite(String file) {
        super.checkWrite(file);
        throw new SecurityException("无写权限checkWrite：" + file);
    }

    @Override
    public void checkDelete(String file) {
        super.checkDelete(file);
        throw new SecurityException("checkDelete：" + file);
    }

    @Override
    public void checkConnect(String host, int port) {
        super.checkConnect(host, port);
        throw new SecurityException("checkConnect：" + host + ":" + port);
    }
}
