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
            adbEnabled = runCatching {
                Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
            }.getOrDefault(false),
            wirelessDebuggingEnabled = runCatching {
                Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
            }.getOrDefault(false),
            port = runCatching {
                Settings.Global.getInt(resolver, "adb_wifi_port", -1)
            }.getOrDefault(-1)
        )
    }

    suspend fun authorize(context: Context): Result<DhizukuAdbState> {
        val appContext = context.applicationContext
        return runCatching {
            check(runCatching { Dhizuku.init(appContext) }.getOrDefault(false)) {
                "Dhizuku no está disponible o no está activo."
            }
            ensurePermission()
            delay(250)
            readState(appContext)
        }
    }

    suspend fun setAdbEnabled(context: Context, enabled: Boolean): Result<DhizukuAdbState> {
        val appContext = context.applicationContext

        return runCatching {
            check(runCatching { Dhizuku.init(appContext) }.getOrDefault(false)) {
                "Dhizuku no está disponible o no está activo."
            }

            ensurePermission()

            val bound = bindService(appContext)
                ?: error("No se pudo conectar al servicio Device Owner de Dhizuku.")

            try {
                check(bound.remote.setAdbEnabled(enabled)) {
                    "Dhizuku no pudo cambiar el estado de ADB."
                }
            } finally {
                closeService(bound)
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

    private data class BoundService(
        val remote: IDhizukuService,
        val connection: ServiceConnection,
        val args: DhizukuUserServiceArgs
    )

    private suspend fun bindService(context: Context): BoundService? {
        return withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val args = DhizukuUserServiceArgs(
                    ComponentName(context, DhizukuService::class.java)
                )
                lateinit var connection: ServiceConnection
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!continuation.isActive) return
                        val service = binder?.let { IDhizukuService.Stub.asInterface(it) }
                        if (service == null) {
                            continuation.resume(null)
                        } else {
                            continuation.resume(BoundService(service, connection, args))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }

                val didBind = runCatching {
                    Dhizuku.bindUserService(args, connection)
                }.getOrDefault(false)

                if (!didBind && continuation.isActive) {
                    continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    runCatching { Dhizuku.stopUserService(args) }
                    runCatching { Dhizuku.unbindUserService(connection) }
                }
            }
        }
    }

    private fun closeService(bound: BoundService) {
        // Dhizuku does not auto-stop user services on newer app versions.
        // Stop first, then unbind, so a stale service cannot poison later calls.
        runCatching { Dhizuku.stopUserService(bound.args) }
        runCatching { Dhizuku.unbindUserService(bound.connection) }
    }

    private const val SERVICE_BIND_TIMEOUT_MS = 10_000L
    private const val WIRELESS_VERIFY_ATTEMPTS = 5
    private const val WIRELESS_VERIFY_INTERVAL_MS = 500L
}
