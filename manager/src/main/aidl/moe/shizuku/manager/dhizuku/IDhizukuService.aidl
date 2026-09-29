package moe.shizuku.manager.dhizuku;

// Dhizuku's UserService manager reserves Binder transaction codes
// FIRST_CALL_TRANSACTION + 1 / + 2 for its lifecycle notifications.
// Keep our application RPC surface away from those codes.
interface IDhizukuService {
    boolean setAdbEnabled(boolean enabled) = 20;
    boolean setWirelessDebuggingEnabled(boolean enabled) = 21;
    boolean enableAdb() = 22;
    int getAdbPort() = 23;
}
