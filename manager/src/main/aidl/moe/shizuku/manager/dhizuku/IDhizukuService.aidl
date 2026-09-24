package moe.shizuku.manager.dhizuku;

interface IDhizukuService {
    void runCommand(String command);
    boolean setAdbEnabled(boolean enabled);
    boolean enableAdb();
    int getAdbPort();
    boolean bindAdbTcp(int port);
}
