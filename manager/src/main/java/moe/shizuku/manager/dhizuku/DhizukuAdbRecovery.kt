package moe.shizuku.manager.dhizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DhizukuAdbState(
    val dhizukuAvailable: Boolean,
    val permissionGranted: Boolean,
    val adbEnabled: Boolean,
    val wirelessDebuggingEnabled: Boolean,
    val port: Int
)

object DhizukuAdbRecovery {

    fun readState(context: Context): DhizukuAdbState {
        val appContext = context.applicationContext
        val initialized = runCatching { Dhizuku.init(appContext) }.getOrDefault(false)
        val granted = initialized && runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        val resolver = appContext.contentResolver

        return DhizukuAdbState(
            dhizukuAvailable = initialized,
            permissionGranted = granted,
            adbEnabled = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1,
            wirelessDebuggingEnabled = Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1,
            port = Settings.Global.getInt(resolver, "adb_wifi_port", -1)
        )
    }

    suspend fun setAdbEnabled(context: Context, enabled: Boolean): Result<DhizukuAdbState> {
        val appContext = context.applicationContext

        return runCatching {
            check(runCatching { Dhizuku.init(appContext) }.getOrDefault(false)) {
                "Dhizuku no está disponible o no está activo."
            }

            ensurePermission()

            val service = bindService(appContext)
                ?: error("No se pudo conectar al servicio Device Owner de Dhizuku.")

            val remote = service.first
            val connection = service.second
            try {
                check(remote.setAdbEnabled(enabled)) {
                    "Dhizuku no pudo cambiar el estado de ADB."
                }
            } finally {
                runCatching { Dhizuku.unbindUserService(connection) }
            }

            delay(700)
            val state = readState(appContext)
            check(state.adbEnabled == enabled) {
                "Android no conservó el estado de ADB solicitado."
            }
            state
        }
    }

    suspend fun enableAdbAndWaitForWireless(context: Context): Result<DhizukuAdbState> {
        return setAdbEnabled(context, true).mapCatching { initial ->
            var current = initial
            repeat(WIRELESS_VERIFY_ATTEMPTS) {
                if (current.wirelessDebuggingEnabled) return@mapCatching current
                delay(WIRELESS_VERIFY_INTERVAL_MS)
                current = readState(context)
            }
            current
        }
    }

    suspend fun bindPersistentTcp(
        context: Context,
        port: Int = 5555
    ): Result<DhizukuAdbState> {
        require(port in 1..65535) { "Puerto ADB inválido." }
        val appContext = context.applicationContext

        return runCatching {
            check(runCatching { Dhizuku.init(appContext) }.getOrDefault(false)) {
                "Dhizuku no está disponible o no está activo."
            }

            ensurePermission()

            val service = bindService(appContext)
                ?: error("No se pudo conectar al servicio Device Owner de Dhizuku.")

            val remote = service.first
            val connection = service.second
            try {
                check(remote.bindAdbTcp(port)) {
                    "Dhizuku no pudo abrir ADB TCP en el puerto $port."
                }
            } finally {
                runCatching { Dhizuku.unbindUserService(connection) }
            }

            delay(500)
            readState(appContext)
        }
    }

    private suspend fun ensurePermission() {
        if (Dhizuku.isPermissionGranted()) return

        val granted = suspendCancellableCoroutine<Boolean> { continuation ->
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    if (continuation.isActive) {
                        continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            })
        }

        check(granted) { "Se denegó el permiso de Dhizuku." }
    }

    private suspend fun bindService(context: Context): Pair<IDhizukuService, ServiceConnection>? {
        return withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var connection: ServiceConnection
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!continuation.isActive) return
                        val service = binder?.let { IDhizukuService.Stub.asInterface(it) }
                        if (service == null) {
                            continuation.resume(null)
                        } else {
                            continuation.resume(service to connection)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }

                val args = DhizukuUserServiceArgs(
                    ComponentName(context, DhizukuService::class.java)
                )

                val bound = runCatching {
                    Dhizuku.bindUserService(args, connection)
                }.getOrDefault(false)

                if (!bound && continuation.isActive) {
                    continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    runCatching { Dhizuku.unbindUserService(connection) }
                }
            }
        }
    }

    private const val SERVICE_BIND_TIMEOUT_MS = 10_000L
    private const val WIRELESS_VERIFY_ATTEMPTS = 5
    private const val WIRELESS_VERIFY_INTERVAL_MS = 500L
}
