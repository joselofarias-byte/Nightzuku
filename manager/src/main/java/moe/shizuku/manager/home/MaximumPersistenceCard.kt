package moe.shizuku.manager.home

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.persistence.DeveloperOptionsController
import moe.shizuku.manager.persistence.PersistenceActions
import moe.shizuku.manager.persistence.PersistenceServiceState
import moe.shizuku.manager.persistence.PersistenceUiMapper
import moe.shizuku.manager.persistence.PersistenceUiModel
import moe.shizuku.manager.persistence.RecoveryTransport
import moe.shizuku.manager.persistence.RecoveryTransportPolicy
import moe.shizuku.manager.persistence.TcpCapability
import moe.shizuku.manager.persistence.TcpHealth
import moe.shizuku.manager.persistence.TcpHealthClassifier
import moe.shizuku.manager.persistence.TcpHealthState
import moe.shizuku.manager.persistence.TransportCandidate
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.ui.compose.ShizukuIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MaximumPersistenceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by NightDogRecovery.snapshot.collectAsStateWithLifecycle()
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var tcpHealth by remember {
        mutableStateOf(
            TcpHealthClassifier.classify(
                ShizukuSettings.isAdbTcpEnabled(),
                ShizukuSettings.getAdbTcpHost(),
                ShizukuSettings.getAdbTcpPort(),
                null,
                null
            )
        )
    }
    var developerState by remember {
        mutableStateOf(PersistenceActions.developerOptionsSnapshot(context))
    }
    var actionStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmStopKeepRunning by remember { mutableStateOf(false) }
    var confirmDisableTcp by remember { mutableStateOf(false) }
    var confirmRecoveryTest by remember { mutableStateOf(false) }
    var confirmDeveloperOff by remember { mutableStateOf(false) }
    var showAndroidSettings by remember { mutableStateOf(false) }

    LaunchedEffect(snapshot.stage, snapshot.lastAttemptElapsedRealtime, snapshot.desiredRunning) {
        nowElapsed = SystemClock.elapsedRealtime()
        while (snapshot.desiredRunning && !snapshot.binderAlive) {
            delay(1_000L)
            nowElapsed = SystemClock.elapsedRealtime()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            developerState = PersistenceActions.developerOptionsSnapshot(context)
            delay(1_500L)
        }
    }

    LaunchedEffect(snapshot.stage, snapshot.endpoint, snapshot.recoveryCount) {
        // ponytail: socket+auth probe after snapshot changes is enough for the card.
        // replace with a dedicated health job if users start hammering Test local TCP.
        tcpHealth = runCatching { PersistenceActions.classifyStoredTcp() }.getOrDefault(tcpHealth)
    }

    val mdns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)
    } else {
        null
    }
    val persistentCandidate = TransportCandidate(
        kind = RecoveryTransport.PERSISTENT_LOCAL_TCP,
        host = ShizukuSettings.getAdbTcpHost(),
        port = ShizukuSettings.getAdbTcpPort(),
        configured = ShizukuSettings.isAdbTcpEnabled(),
        socketReachable = tcpHealth.state == TcpHealthState.ENABLED_REACHABLE,
        authenticated = tcpHealth.usableForRestart
    ).takeIf { ShizukuSettings.isAdbTcpEnabled() }
    val mdnsCandidate = mdns?.let {
        TransportCandidate(
            kind = RecoveryTransport.MDNS_WIRELESS_DEBUGGING,
            host = it.host,
            port = it.port,
            configured = true,
            socketReachable = true
        )
    }
    val displayTransport = RecoveryTransportPolicy.selectForDisplay(
        binderAlive = snapshot.binderAlive,
        persistent = persistentCandidate,
        mdns = mdnsCandidate,
        systemTcp = null
    )
    val model = PersistenceUiMapper.map(
        desiredRunning = snapshot.desiredRunning,
        binderAlive = snapshot.binderAlive,
        stage = snapshot.stage.name,
        snapshotEndpoint = snapshot.endpoint,
        serverPid = snapshot.serverPid,
        recoveryCount = snapshot.recoveryCount,
        lastResultKey = snapshot.lastResultKey,
        lastFailure = snapshot.lastFailure,
        retryRemainingMs = PersistenceActions.retryRemainingMs(snapshot, nowElapsed),
        reactivationRequired = snapshot.reactivationRequired,
        tcp = tcpHealth,
        displayTransport = displayTransport
    )

    PersistenceCardBody(
        model = model,
        developerState = developerState,
        lastResultText = lastResultText(model),
        actionStatus = actionStatus,
        busy = busy,
        onDesiredChange = { desired ->
            if (!desired) confirmStopKeepRunning = true
            else PersistenceActions.setDesiredRunning(context, true)
        },
        onRecoverNow = {
            PersistenceActions.recoverNow(context)
            actionStatus = context.getString(R.string.persistence_result_recover_now)
        },
        onEnableTcp = {
            if (busy) return@PersistenceCardBody
            busy = true
            actionStatus = context.getString(R.string.persistence_action_busy)
            scope.launch {
                val result = PersistenceActions.enableLocalTcp()
                actionStatus = if (result.success) {
                    result.message
                } else {
                    result.message.ifBlank { context.getString(R.string.persistence_enable_tcp_need_wadb) }
                }
                tcpHealth = PersistenceActions.classifyStoredTcp()
                busy = false
            }
        },
        onTestTcp = {
            if (busy) return@PersistenceCardBody
            busy = true
            actionStatus = context.getString(R.string.persistence_action_busy)
            scope.launch {
                val result = PersistenceActions.testLocalTcp()
                actionStatus = result.message
                tcpHealth = PersistenceActions.classifyStoredTcp()
                busy = false
            }
        },
        onDisableTcp = { confirmDisableTcp = true },
        onTestRecovery = { confirmRecoveryTest = true },
        onPrepareDeveloperControl = {
            if (busy) return@PersistenceCardBody
            busy = true
            actionStatus = context.getString(R.string.persistence_action_busy)
            scope.launch {
                val result = PersistenceActions.prepareDeveloperOptionsControl(context)
                developerState = result.snapshot
                actionStatus = if (result.success) {
                    context.getString(R.string.persistence_developer_control_prepared)
                } else {
                    context.getString(
                        R.string.persistence_developer_action_failed,
                        result.detail ?: context.getString(R.string.persistence_developer_unknown_error)
                    )
                }
                busy = false
            }
        },
        onDeveloperOff = { confirmDeveloperOff = true },
        onDeveloperRestore = {
            if (busy) return@PersistenceCardBody
            busy = true
            actionStatus = context.getString(R.string.persistence_action_busy)
            scope.launch {
                val result = PersistenceActions.restoreDeveloperOptions(context)
                developerState = result.snapshot
                actionStatus = if (result.success) {
                    context.getString(R.string.persistence_developer_restored)
                } else {
                    context.getString(
                        R.string.persistence_developer_action_failed,
                        result.detail ?: context.getString(R.string.persistence_developer_unknown_error)
                    )
                }
                busy = false
            }
        },
        onOpenSettings = { showAndroidSettings = true }
    )

    if (confirmStopKeepRunning) {
        ConfirmDialog(
            title = R.string.persistence_keep_running_confirm_title,
            message = R.string.persistence_keep_running_confirm_message,
            confirm = R.string.persistence_keep_running,
            onDismiss = { confirmStopKeepRunning = false },
            onConfirm = {
                confirmStopKeepRunning = false
                PersistenceActions.setDesiredRunning(context, false)
            }
        )
    }
    if (confirmDisableTcp) {
        ConfirmDialog(
            title = R.string.persistence_disable_tcp_title,
            message = R.string.persistence_disable_tcp_message,
            confirm = R.string.persistence_action_disable_tcp,
            onDismiss = { confirmDisableTcp = false },
            onConfirm = {
                confirmDisableTcp = false
                if (busy) return@ConfirmDialog
                busy = true
                actionStatus = context.getString(R.string.persistence_action_busy)
                scope.launch {
                    val result = PersistenceActions.disableLocalTcp()
                    actionStatus = result.message
                    tcpHealth = PersistenceActions.classifyStoredTcp()
                    busy = false
                }
            }
        )
    }
    if (confirmDeveloperOff) {
        ConfirmDialog(
            title = R.string.persistence_developer_off_confirm_title,
            message = R.string.persistence_developer_off_confirm_message,
            confirm = R.string.persistence_developer_off,
            onDismiss = { confirmDeveloperOff = false },
            onConfirm = {
                confirmDeveloperOff = false
                if (busy) return@ConfirmDialog
                busy = true
                actionStatus = context.getString(R.string.persistence_action_busy)
                scope.launch {
                    val result = PersistenceActions.disableDeveloperOptionsTemporarily(context)
                    developerState = result.snapshot
                    actionStatus = if (result.success) {
                        context.getString(R.string.persistence_developer_disabled)
                    } else {
                        context.getString(
                            R.string.persistence_developer_action_failed,
                            result.detail ?: context.getString(R.string.persistence_developer_unknown_error)
                        )
                    }
                    busy = false
                }
            }
        )
    }
    if (confirmRecoveryTest) {
        ConfirmDialog(
            title = R.string.persistence_test_recovery_title,
            message = R.string.persistence_test_recovery_message,
            confirm = R.string.persistence_test_recovery_start,
            onDismiss = { confirmRecoveryTest = false },
            onConfirm = {
                confirmRecoveryTest = false
                if (busy) return@ConfirmDialog
                busy = true
                actionStatus = context.getString(R.string.persistence_action_busy)
                scope.launch {
                    val report = runCatching { PersistenceActions.testRecovery(context) }
                        .getOrElse { error ->
                            actionStatus = error.message ?: error.javaClass.simpleName
                            busy = false
                            return@launch
                        }
                    actionStatus = if (report.success) {
                        val seconds = report.elapsedSeconds ?: 0
                        val pid = report.recoveredPid
                        if (pid != null) {
                            context.getString(R.string.persistence_test_recovery_success, seconds, pid)
                        } else {
                            context.getString(R.string.persistence_test_recovery_success_no_pid, seconds)
                        }
                    } else {
                        context.getString(
                            R.string.persistence_test_recovery_failed,
                            report.elapsedSeconds ?: 30,
                            report.lastStage
                        )
                    }
                    tcpHealth = PersistenceActions.classifyStoredTcp()
                    busy = false
                }
            }
        )
    }
    if (showAndroidSettings) {
        AlertDialog(
            onDismissRequest = { showAndroidSettings = false },
            title = { Text(stringResource(R.string.persistence_action_open_settings)) },
            text = { Text(stringResource(R.string.persistence_honest_reboot_limit)) },
            confirmButton = {
                TextButton(onClick = {
                    val opened = PersistenceActions.openWirelessDebugging(context)
                    if (!opened) {
                        Toast.makeText(context, R.string.persistence_settings_missing, Toast.LENGTH_LONG).show()
                    }
                    showAndroidSettings = false
                }) { Text(stringResource(R.string.persistence_open_wireless_debugging)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val opened = PersistenceActions.openDeveloperOptions(context)
                    if (!opened) {
                        Toast.makeText(context, R.string.persistence_settings_missing, Toast.LENGTH_LONG).show()
                    }
                    showAndroidSettings = false
                }) { Text(stringResource(R.string.persistence_open_developer_options)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersistenceCardBody(
    model: PersistenceUiModel,
    developerState: DeveloperOptionsController.Snapshot,
    lastResultText: String,
    actionStatus: String?,
    busy: Boolean,
    onDesiredChange: (Boolean) -> Unit,
    onRecoverNow: () -> Unit,
    onEnableTcp: () -> Unit,
    onTestTcp: () -> Unit,
    onDisableTcp: () -> Unit,
    onTestRecovery: () -> Unit,
    onPrepareDeveloperControl: () -> Unit,
    onDeveloperOff: () -> Unit,
    onDeveloperRestore: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val statusIcon = when (model.service) {
        PersistenceServiceState.RUNNING -> R.drawable.ic_server_ok_24dp
        PersistenceServiceState.ERROR -> R.drawable.ic_warning_24
        PersistenceServiceState.MANUALLY_STOPPED -> R.drawable.ic_server_error_24dp
        PersistenceServiceState.RECOVERING,
        PersistenceServiceState.WAITING_FOR_ADB -> R.drawable.ic_server_restart
    }

    val developerGlyph = if (developerState.developerOptionsEnabled) "✓" else "×"
    val adbGlyph = if (developerState.adbEnabled) "✓" else "×"
    val wirelessGlyph = if (developerState.wirelessDebuggingEnabled) "✓" else "×"
    val endpoint = model.endpoint?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.persistence_endpoint_none)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                ShizukuIcon(
                    icon = statusIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(9.dp).size(22.dp)
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.persistence_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            serviceLabel(model.service),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = model.desiredRunning,
                        enabled = !busy,
                        onCheckedChange = onDesiredChange
                    )
                }

                Text(
                    "Dev $developerGlyph · ADB $adbGlyph · Wi-Fi ADB $wirelessGlyph",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "${transportLabel(model.transport)} · $endpoint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (developerState.restorePending) {
                    Text(
                        stringResource(R.string.persistence_developer_restore_pending),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (!actionStatus.isNullOrBlank()) {
                    Text(
                        actionStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        enabled = !busy,
                        onClick = onRecoverNow,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        ButtonIcon(R.drawable.ic_server_restart)
                        Text(stringResource(R.string.persistence_action_recover_now))
                    }

                    when {
                        !developerState.writeSecureSettingsGranted -> {
                            FilledTonalButton(
                                enabled = !busy,
                                onClick = onPrepareDeveloperControl,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                ButtonIcon(R.drawable.ic_settings_outline_24dp)
                                Text(stringResource(R.string.persistence_prepare_developer_control))
                            }
                        }

                        developerState.restorePending || !developerState.developerOptionsEnabled -> {
                            FilledTonalButton(
                                enabled = !busy,
                                onClick = onDeveloperRestore,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                ButtonIcon(R.drawable.ic_server_restart)
                                Text(stringResource(R.string.persistence_developer_restore))
                            }
                        }

                        else -> {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = onDeveloperOff,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                ButtonIcon(R.drawable.ic_close_24)
                                Text(stringResource(R.string.persistence_developer_off))
                            }
                        }
                    }

                    TextButton(
                        onClick = { expanded = !expanded },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                    ) {
                        Text(
                            stringResource(
                                if (expanded) R.string.persistence_hide_details
                                else R.string.persistence_show_details
                            )
                        )
                    }
                }

                if (expanded) {
                    Text(
                        stringResource(R.string.persistence_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Fact(
                        R.string.persistence_developer_control,
                        stringResource(
                            if (developerState.writeSecureSettingsGranted) {
                                R.string.persistence_developer_control_ready
                            } else {
                                R.string.persistence_developer_control_not_ready
                            }
                        )
                    )
                    Fact(
                        R.string.persistence_developer_options,
                        stringResource(
                            if (developerState.developerOptionsEnabled) {
                                R.string.persistence_toggle_enabled
                            } else {
                                R.string.persistence_toggle_disabled
                            }
                        )
                    )
                    Fact(
                        R.string.persistence_adb_global,
                        stringResource(
                            if (developerState.adbEnabled) {
                                R.string.persistence_toggle_enabled
                            } else {
                                R.string.persistence_toggle_disabled
                            }
                        )
                    )
                    Fact(
                        R.string.persistence_wireless_debugging_state,
                        stringResource(
                            if (developerState.wirelessDebuggingEnabled) {
                                R.string.persistence_toggle_enabled
                            } else {
                                R.string.persistence_toggle_disabled
                            }
                        )
                    )
                    Fact(R.string.persistence_transport, transportLabel(model.transport))
                    Fact(R.string.persistence_endpoint, endpoint)
                    Fact(R.string.persistence_tcp_state, tcpStateLabel(model.tcp))
                    Fact(
                        R.string.persistence_pid,
                        model.serverPid?.toString()
                            ?: stringResource(R.string.persistence_pid_unknown)
                    )
                    Fact(R.string.persistence_recovery_count, model.recoveryCount.toString())
                    Fact(R.string.persistence_last_result, lastResultText)
                    Fact(
                        R.string.persistence_last_failure,
                        model.lastFailure?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.persistence_last_failure_none)
                    )
                    Fact(
                        R.string.persistence_retry,
                        if (model.retryRemainingMs > 0L) {
                            stringResource(
                                R.string.persistence_retry_in,
                                ((model.retryRemainingMs + 999) / 1000).toInt()
                            )
                        } else {
                            stringResource(R.string.persistence_retry_now)
                        }
                    )

                    HonestyBanner(model)

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = onEnableTcp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            ButtonIcon(R.drawable.ic_adb_24dp)
                            Text(stringResource(R.string.persistence_action_enable_tcp))
                        }
                        FilledTonalButton(
                            enabled = !busy,
                            onClick = onTestTcp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            ButtonIcon(R.drawable.ic_server_ok_24dp)
                            Text(stringResource(R.string.persistence_action_test_tcp))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = onDisableTcp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            ButtonIcon(R.drawable.ic_close_24)
                            Text(stringResource(R.string.persistence_action_disable_tcp))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = onTestRecovery,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            ButtonIcon(R.drawable.ic_warning_24)
                            Text(stringResource(R.string.persistence_action_test_recovery))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = onOpenSettings,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            ButtonIcon(R.drawable.ic_settings_outline_24dp)
                            Text(stringResource(R.string.persistence_action_open_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HonestyBanner(model: PersistenceUiModel) {
    val text = when {
        model.localTcpRecoveryAvailable -> stringResource(R.string.persistence_honest_tcp_available)
        model.wirelessActivationRequired -> stringResource(R.string.persistence_honest_wadb_required)
        else -> null
    }
    if (text != null) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (model.localTcpRecoveryAvailable) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
    Text(
        stringResource(
            if (DeveloperOptionsController.snapshot(LocalContext.current).writeSecureSettingsGranted) {
                R.string.persistence_honest_reboot_prepared
            } else {
                R.string.persistence_honest_reboot_limit
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Fact(@StringRes label: Int, value: String) {
    Text(
        "${stringResource(label)}: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    ShizukuIcon(
        icon = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp).size(18.dp)
    )
}

@Composable
private fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes confirm: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun serviceLabel(state: PersistenceServiceState): String = stringResource(
    when (state) {
        PersistenceServiceState.RUNNING -> R.string.persistence_service_running
        PersistenceServiceState.RECOVERING -> R.string.persistence_service_recovering
        PersistenceServiceState.WAITING_FOR_ADB -> R.string.persistence_service_waiting_adb
        PersistenceServiceState.ERROR -> R.string.persistence_service_error
        PersistenceServiceState.MANUALLY_STOPPED -> R.string.persistence_service_stopped
    }
)

@Composable
private fun transportLabel(transport: RecoveryTransport): String = stringResource(
    when (transport) {
        RecoveryTransport.BINDER_ALIVE -> R.string.persistence_transport_binder
        RecoveryTransport.PERSISTENT_LOCAL_TCP -> R.string.persistence_transport_tcp
        RecoveryTransport.MDNS_WIRELESS_DEBUGGING -> R.string.persistence_transport_mdns
        RecoveryTransport.DYNAMIC_LOCAL_WIRELESS_ADB -> R.string.persistence_transport_dynamic_local
        RecoveryTransport.SYSTEM_ADB_TCP -> R.string.persistence_transport_system
        RecoveryTransport.NONE -> R.string.persistence_transport_none
    }
)

@Composable
private fun tcpStateLabel(health: TcpHealth): String {
    val state = stringResource(
        when (health.state) {
            TcpHealthState.ENABLED_REACHABLE -> R.string.persistence_tcp_enabled_reachable
            TcpHealthState.ENABLED_UNREACHABLE -> R.string.persistence_tcp_enabled_unreachable
            TcpHealthState.DISABLED -> R.string.persistence_tcp_disabled
            TcpHealthState.UNKNOWN -> R.string.persistence_tcp_unknown
        }
    )
    val capability = stringResource(
        when (health.capability) {
            TcpCapability.USABLE_FOR_RESTART,
            TcpCapability.ADB_AUTHENTICATED -> R.string.persistence_tcp_capability_authenticated
            TcpCapability.SOCKET_REACHABLE -> R.string.persistence_tcp_capability_socket
            TcpCapability.CONFIGURED -> R.string.persistence_tcp_capability_configured
            TcpCapability.NOT_CONFIGURED -> R.string.persistence_tcp_capability_not_configured
        }
    )
    return "$state · $capability"
}

@Composable
private fun lastResultText(model: PersistenceUiModel): String {
    val mapped = when (model.lastResultKey) {
        NightDogRecovery.RESULT_NO_CHECKS -> R.string.persistence_result_no_checks
        NightDogRecovery.RESULT_BINDER_RECEIVED -> R.string.persistence_result_binder_received
        NightDogRecovery.RESULT_BINDER_RESPONDING -> R.string.persistence_result_binder_responding
        NightDogRecovery.RESULT_BINDER_ALREADY_ALIVE -> R.string.persistence_result_binder_already_alive
        NightDogRecovery.RESULT_BINDER_LOST -> R.string.persistence_result_binder_lost
        NightDogRecovery.RESULT_BINDER_UNRESPONSIVE -> R.string.persistence_result_binder_unresponsive
        NightDogRecovery.RESULT_MANUALLY_STOPPED -> R.string.persistence_result_manually_stopped
        NightDogRecovery.RESULT_RECOVERY_SKIPPED -> R.string.persistence_result_recovery_skipped
        NightDogRecovery.RESULT_WATCHDOG_STOPPED -> R.string.persistence_result_watchdog_stopped
        NightDogRecovery.RESULT_MDNS_FOUND -> R.string.persistence_result_mdns_found
        NightDogRecovery.RESULT_DISCOVERING -> R.string.persistence_result_discovering
        NightDogRecovery.RESULT_START_REQUESTED -> R.string.persistence_result_start_requested
        NightDogRecovery.RESULT_NO_USABLE_ENDPOINT -> R.string.persistence_result_no_usable_endpoint
        NightDogRecovery.RESULT_STARTER_FAILED -> R.string.persistence_result_starter_failed
        NightDogRecovery.RESULT_WAITING_RETRY -> R.string.persistence_result_waiting_retry
        NightDogRecovery.RESULT_APP_START -> R.string.persistence_result_app_start
        NightDogRecovery.RESULT_MANUAL_START -> R.string.persistence_result_manual_start
        NightDogRecovery.RESULT_RECOVER_NOW -> R.string.persistence_result_recover_now
        else -> null
    }
    return mapped?.let { stringResource(it) } ?: model.lastResultKey
}
