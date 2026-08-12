package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRecoveryTestController
import moe.shizuku.manager.adb.AdbTcpController
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.NightzukuSwitchSettingsRow
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import rikka.shizuku.Shizuku

class LabFeaturesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val scope = rememberCoroutineScope()
            var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
            var tcpEnabled by remember { mutableStateOf(ShizukuSettings.isAdbTcpEnabled()) }
            var tcpHost by remember { mutableStateOf(ShizukuSettings.getAdbTcpHost()) }
            var tcpPort by remember { mutableStateOf(ShizukuSettings.getAdbTcpPort().toString()) }
            var tcpError by remember { mutableStateOf<String?>(null) }
            var tcpBusy by remember { mutableStateOf(false) }
            var tcpStatus by remember { mutableStateOf<String?>(null) }
            var killBusy by remember { mutableStateOf(false) }
            var killStatus by remember { mutableStateOf<String?>(null) }
            var showUnsafeDialog by remember { mutableStateOf(false) }
            var showTcpDialog by remember { mutableStateOf(false) }
            var showKillDialog by remember { mutableStateOf(false) }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@LabFeaturesActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                moe.shizuku.manager.ui.compose.WearScreenTitle(
                                    icon = Icons.Rounded.Code,
                                    title = stringResource(R.string.lab_features_title)
                                )
                            }
                            item {
                                WearSwitchButton(
                                    checked = connectorEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) showUnsafeDialog = true
                                        else {
                                            connectorEnabled = false
                                            ModuleSettings.setConnectorEnabled(false)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { WearText(stringResource(R.string.shizuku_connectors_title)) },
                                    secondaryLabel = { WearText(stringResource(R.string.shizuku_connectors_summary)) },
                                    icon = {
                                        WearIcon(
                                            painter = painterResource(R.drawable.ic_baseline_link_24),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (showUnsafeDialog) {
                        WearAlertDialog(
                            show = true,
                            onDismissRequest = { showUnsafeDialog = false },
                            title = { WearText(stringResource(R.string.unsafe_warning_title)) },
                            text = { WearText(stringResource(R.string.unsafe_warning_message)) },
                            confirmButton = {
                                WearButton(onClick = {
                                    showUnsafeDialog = false
                                    connectorEnabled = true
                                    ModuleSettings.setConnectorEnabled(true)
                                }) { WearText(stringResource(android.R.string.ok)) }
                            },
                            dismissButton = {
                                WearFilledTonalButton(onClick = { showUnsafeDialog = false }) {
                                    WearText(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }
                }
            } else {
                ShizukuExpressiveTheme {
                    ShizukuLazyScaffold(
                        title = stringResource(R.string.lab_features_title),
                        onNavigateUp = { finish() }
                    ) {
                        item {
                            SettingsGroup(title = stringResource(R.string.lab_features_summary)) {
                                NightzukuSwitchSettingsRow(
                                    icon = R.drawable.ic_baseline_link_24,
                                    title = stringResource(R.string.shizuku_connectors_title),
                                    summary = stringResource(R.string.shizuku_connectors_summary),
                                    checked = connectorEnabled,
                                    onCheckedChange = { enabled ->
                                        if (enabled) showUnsafeDialog = true
                                        else {
                                            connectorEnabled = false
                                            ModuleSettings.setConnectorEnabled(false)
                                        }
                                    }
                                )
                            }
                        }

                        item {
                            SettingsGroup(title = "ADB TCP/IP") {
                                NightzukuSwitchSettingsRow(
                                    icon = R.drawable.ic_adb_24dp,
                                    title = "Transporte TCP persistente",
                                    summary = tcpStatus ?: if (tcpEnabled) "$tcpHost:$tcpPort" else "Desactivado; mDNS inalámbrico sigue siendo preferido",
                                    checked = tcpEnabled,
                                    enabled = !tcpBusy,
                                    onCheckedChange = { enabled ->
                                        if (!tcpBusy) {
                                            tcpStatus = null
                                            if (enabled) {
                                                tcpError = null
                                                showTcpDialog = true
                                            } else {
                                                tcpBusy = true
                                                scope.launch {
                                                    val result = AdbTcpController.disable()
                                                    tcpBusy = false
                                                    tcpStatus = result.message
                                                    if (result.success) tcpEnabled = false
                                                }
                                            }
                                        }
                                    }
                                )
                                GroupDivider()
                                SettingsRow(
                                    icon = R.drawable.ic_settings_outline_24dp,
                                    title = "Dirección TCP",
                                    summary = "$tcpHost:$tcpPort",
                                    onClick = {
                                        if (tcpBusy) return@SettingsRow
                                        tcpError = null
                                        tcpStatus = null
                                        showTcpDialog = true
                                    }
                                )
                            }
                        }

                        item {
                            SettingsGroup(title = "NightDog · recuperación") {
                                SettingsRow(
                                    icon = R.drawable.ic_warning_24,
                                    title = "Probar recuperación automática",
                                    summary = killStatus ?: if (killBusy) {
                                        "Prueba en curso…"
                                    } else {
                                        "Detiene el servidor y comprueba que NightDog lo restaure."
                                    },
                                    onClick = {
                                        if (!killBusy) showKillDialog = true
                                    }
                                )
                            }
                        }
                    }

                    if (showUnsafeDialog) {
                        AlertDialog(
                            onDismissRequest = { showUnsafeDialog = false },
                            title = { Text(stringResource(R.string.unsafe_warning_title)) },
                            text = { Text(stringResource(R.string.unsafe_warning_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showUnsafeDialog = false
                                    connectorEnabled = true
                                    ModuleSettings.setConnectorEnabled(true)
                                }) { Text(stringResource(android.R.string.ok)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUnsafeDialog = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                        )
                    }

                    if (showKillDialog) {
                        AlertDialog(
                            onDismissRequest = { if (!killBusy) showKillDialog = false },
                            title = { Text("Probar recuperación automática") },
                            text = {
                                Text("Nightzuku detendrá el servidor una vez. NightDog debería iniciarlo de nuevo automáticamente.")
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !killBusy,
                                    onClick = {
                                        killBusy = true
                                        killStatus = "Preparando prueba…"
                                        showKillDialog = false
                                        scope.launch {
                                            val result = runCatching { killServerProcessForRecoveryTest() }
                                            val killed = result.getOrElse { error ->
                                                killBusy = false
                                                killStatus = "Error: ${error.message ?: error.javaClass.simpleName}"
                                                return@launch
                                            }

                                            val killedPid = killed.pid
                                            killStatus = if (killedPid != null) {
                                                "Servidor detenido · PID $killedPid · esperando a NightDog…"
                                            } else {
                                                "Servidor detenido · esperando a NightDog…"
                                            }

                                            for (second in 1..30) {
                                                delay(1_000L)
                                                val snapshot = NightDogRecovery.snapshot.value
                                                val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                                                val newPid = snapshot.serverPid
                                                val pidChanged = killedPid == null || (newPid != null && newPid != killedPid)

                                                if (binderAlive && pidChanged) {
                                                    killBusy = false
                                                    killStatus = buildString {
                                                        append("Recuperado en ${second}s")
                                                        if (newPid != null) append(" · PID $newPid")
                                                    }
                                                    return@launch
                                                }

                                                killStatus = "${recoveryStageLabel(snapshot.stage)} · ${second}s"
                                            }

                                            killBusy = false
                                            killStatus = "No se confirmó la recuperación en 30 s."
                                        }
                                    }
                                ) { Text("Iniciar prueba") }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !killBusy,
                                    onClick = { showKillDialog = false }
                                ) { Text(stringResource(android.R.string.cancel)) }
                            }
                        )
                    }

                    if (showTcpDialog) {
                        AlertDialog(
                            onDismissRequest = { if (!tcpBusy) showTcpDialog = false },
                            title = { Text("Activar ADB TCP persistente") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Nightzuku usará la sesión autenticada de depuración inalámbrica para reiniciar adbd en este puerto TCP y solo guardará la dirección después de verificarla.")
                                    OutlinedTextField(
                                        value = tcpHost,
                                        onValueChange = {
                                            tcpHost = it
                                            tcpError = null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Host") },
                                        singleLine = true,
                                        enabled = !tcpBusy
                                    )
                                    OutlinedTextField(
                                        value = tcpPort,
                                        onValueChange = {
                                            tcpPort = it.filter(Char::isDigit).take(5)
                                            tcpError = null
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Puerto") },
                                        singleLine = true,
                                        enabled = !tcpBusy,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = tcpError != null,
                                        supportingText = tcpError?.let { message -> { Text(message) } }
                                    )
                                    if (tcpBusy) Text("Aplicando y verificando modo TCP…")
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !tcpBusy,
                                    onClick = {
                                        val port = tcpPort.toIntOrNull()
                                        if (tcpHost.isBlank() || port == null || port !in 1..65535) {
                                            tcpError = "Ingresa un host y un puerto entre 1 y 65535"
                                            return@TextButton
                                        }
                                        tcpBusy = true
                                        tcpError = null
                                        scope.launch {
                                            val result = AdbTcpController.enable(tcpHost, port)
                                            tcpBusy = false
                                            tcpStatus = result.message
                                            if (result.success) {
                                                tcpHost = ShizukuSettings.getAdbTcpHost()
                                                tcpPort = ShizukuSettings.getAdbTcpPort().toString()
                                                tcpEnabled = true
                                                showTcpDialog = false
                                            } else {
                                                tcpError = result.message
                                            }
                                        }
                                    }
                                ) { Text("Activar") }
                            },
                            dismissButton = {
                                TextButton(
                                    enabled = !tcpBusy,
                                    onClick = { showTcpDialog = false }
                                ) { Text(stringResource(android.R.string.cancel)) }
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun killServerProcessForRecoveryTest(): AdbRecoveryTestController.Result {
        check(Shizuku.pingBinder()) {
            "Nightzuku no está en ejecución."
        }

        NightDogRecovery.requestManualStart(applicationContext)
        check(NightDogRecovery.snapshot.value.desiredRunning) {
            "No se pudo activar NightDog."
        }

        val result = AdbRecoveryTestController.killServerProcess()
        check(result.success) { result.message }
        return result
    }

    private fun recoveryStageLabel(stage: NightDogRecovery.Stage): String = when (stage) {
        NightDogRecovery.Stage.CHECKING_BINDER -> "Comprobando servicio"
        NightDogRecovery.Stage.DISCOVERING_ADB -> "Buscando conexión ADB"
        NightDogRecovery.Stage.WAITING_FOR_ADB -> "Esperando conexión ADB"
        NightDogRecovery.Stage.STARTING_SERVICE -> "Iniciando Nightzuku"
        NightDogRecovery.Stage.ERROR -> "Error durante la recuperación"
        else -> "Esperando a NightDog"
    }
}
