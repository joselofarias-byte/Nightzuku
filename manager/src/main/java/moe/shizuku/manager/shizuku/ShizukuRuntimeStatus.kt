package moe.shizuku.manager.shizuku

sealed class ShizukuRuntimeStatus {
    data object Connecting : ShizukuRuntimeStatus()
    data object Running : ShizukuRuntimeStatus()
    data object NotRunning : ShizukuRuntimeStatus()
    data object PermissionRequired : ShizukuRuntimeStatus()
    data class Error(val cause: Throwable) : ShizukuRuntimeStatus()
}
