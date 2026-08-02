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
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbTcpController
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow

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
            var showUnsafeDialog by remember { mutableStateOf(false) }
            var showTcpDialog by remember { mutableStateOf(false) }

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
                                SwitchSettingsRow(
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
                                SwitchSettingsRow(
                                    icon = R.drawable.ic_adb_24dp,
                                    title = "Persistent TCP transport",
                                    summary = tcpStatus ?: if (tcpEnabled) "$tcpHost:$tcpPort" else "Disabled; wireless mDNS remains preferred",
                                    checked = tcpEnabled,
                                    onCheckedChange = { enabled ->
                                        if (tcpBusy) return@SwitchSettingsRow
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
                                )
                                GroupDivider()
                                SettingsRow(
                                    icon = R.drawable.ic_settings_outline_24dp,
                                    title = "TCP endpoint",
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

                    if (showTcpDialog) {
                        AlertDialog(
                            onDismissRequest = { if (!tcpBusy) showTcpDialog = false },
                            title = { Text("Enable persistent ADB TCP") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Nightzuku will use the authenticated wireless-debugging session to restart adbd on this TCP port, verify that it is reachable, and save it only after verification.")
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
                                        label = { Text("Port") },
                                        singleLine = true,
                                        enabled = !tcpBusy,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = tcpError != null,
                                        supportingText = tcpError?.let { message -> { Text(message) } }
                                    )
                                    if (tcpBusy) Text("Applying and verifying TCP mode…")
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = !tcpBusy,
                                    onClick = {
                                        val port = tcpPort.toIntOrNull()
                                        if (tcpHost.isBlank() || port == null || port !in 1..65535) {
                                            tcpError = "Enter a host and a port from 1 to 65535"
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
                                ) { Text("Enable") }
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
}
