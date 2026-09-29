package moe.shizuku.manager.dhizuku;

interface IDhizukuService {
    boolean setAdbEnabled(boolean enabled);
    boolean setWirelessDebuggingEnabled(boolean enabled);
    boolean enableAdb();
    int getAdbPort();
}
