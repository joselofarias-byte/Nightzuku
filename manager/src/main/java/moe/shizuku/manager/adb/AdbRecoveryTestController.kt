package moe.shizuku.manager.adb

import kotlinx.coroutines.CancellationException
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
        val context = moe.shizuku.manager.application
        val primary = AdbTransportResolver.discover(context)
            ?: return@withContext Result(
                false,
                "No hay un endpoint ADB local disponible para ejecutar la prueba."
            )

        var endpoint = primary
        var firstFailure: String? = null
        while (true) {
            var launchSubmitted = false
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                val output = ByteArrayOutputStream()
                AdbClient(
                    endpoint.host,
                    endpoint.port,
                    key,
                    requireTls = endpoint.requireTls,
                    allowPublicKeyPrompt = false
                ).use { client ->
                    client.connect()
                    if (!client.verifyShell()) {
                        throw AdbShellUnavailableException("El transporte ADB no permitió ejecutar shell")
                    }
                    launchSubmitted = true
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
                    return@withContext Result(false, "No se encontró el proceso shizuku_server.")
                }
                val pid = Regex("__PID__=(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@withContext Result(false, "ADB respondió, pero no informó el PID del servidor.")

                return@withContext Result(
                    true,
                    "SIGKILL programado para PID $pid. NightDog debe detectar la pérdida del Binder y recuperar el servicio.",
                    pid
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = "${endpoint.host}:${endpoint.port}: ${error.message ?: error.javaClass.simpleName}"
                val alternate = if (endpoint == primary &&
                    AdbTransportResolver.canTryAlternate(error, launchSubmitted)
                ) AdbTransportResolver.alternateLocalEndpoint(context, endpoint) else null
                if (alternate != null) {
                    firstFailure = failure
                    endpoint = alternate
                    continue
                }
                val details = listOfNotNull(firstFailure, failure).joinToString(" · ")
                return@withContext Result(false, "La prueba ADB no confirmó la parada. $details")
            }
        }

        @Suppress("UNREACHABLE_CODE")
        Result(false, "La prueba ADB no se completó.")
    }
}
