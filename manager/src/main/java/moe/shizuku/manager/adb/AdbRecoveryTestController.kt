package moe.shizuku.manager.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import java.io.ByteArrayOutputStream

/** Executes destructive NightDog recovery tests through Nightzuku's authenticated ADB transport. */
object AdbRecoveryTestController {

    data class Result(
        val success: Boolean,
        val message: String,
        val pid: Int? = null
    )

    suspend fun killServerProcess(): Result = withContext(Dispatchers.IO) {
        val endpoint = AdbTransportResolver.persistentTcpEndpoint()
            ?: AdbTransportResolver.systemAdbTcpEndpoint()
            ?: AdbMdns.getResolvedEndpoint(AdbMdns.TLS_CONNECT)
            ?: return@withContext Result(
                false,
                "No usable ADB endpoint is available for the recovery test."
            )

        runCatching {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            val output = ByteArrayOutputStream()
            AdbClient(endpoint.host, endpoint.port, key).use { client ->
                client.connect()
                client.shellCommand(
                    "pid=\"\$(pidof shizuku_server 2>/dev/null | cut -d' ' -f1)\"; " +
                        "[ -n \"\$pid\" ] || { echo __NO_PID__; exit; }; " +
                        "echo __PID__=\$pid; " +
                        "(sleep 1; kill -9 \"\$pid\") >/dev/null 2>&1 &",
                    output::write
                )
            }

            val text = output.toString(Charsets.UTF_8.name()).trim()
            if ("__NO_PID__" in text) {
                return@runCatching Result(false, "shizuku_server process was not found.")
            }
            val pid = Regex("__PID__=(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@runCatching Result(false, "ADB responded but did not report the server PID.")

            Result(
                true,
                "SIGKILL scheduled for PID $pid. NightDog should detect binder loss and recover the service.",
                pid
            )
        }.getOrElse { error ->
            Result(
                false,
                "Could not run the ADB recovery test at ${endpoint.host}:${endpoint.port}: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }
}
