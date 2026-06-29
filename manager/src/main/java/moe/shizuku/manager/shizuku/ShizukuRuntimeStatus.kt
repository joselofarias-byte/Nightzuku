package moe.shizuku.manager.shizuku

sealed class ShizukuRuntimeStatus {
    abstract val lastChecked: Long

    data object Connecting : ShizukuRuntimeStatus() {
        override val lastChecked: Long get() = 0L
    }

    data class Running(
        override val lastChecked: Long = System.currentTimeMillis()
    ) : ShizukuRuntimeStatus()

    data class NotRunning(
        override val lastChecked: Long = System.currentTimeMillis()
    ) : ShizukuRuntimeStatus()

    data class PermissionRequired(
        override val lastChecked: Long = System.currentTimeMillis()
    ) : ShizukuRuntimeStatus()

    data class Error(
        val cause: Throwable,
        override val lastChecked: Long = System.currentTimeMillis()
    ) : ShizukuRuntimeStatus()
}
