package moe.shizuku.manager.settings

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.SwitchButton as WearSwitchButton
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.nightdog.NightDogBulldogState
import moe.shizuku.manager.nightdog.NightDogManager
import moe.shizuku.manager.nightdog.NightDogPhase
import moe.shizuku.manager.nightdog.NightDogTestPhase
import moe.shizuku.manager.nightdog.NightDogTrigger
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.SwitchSettingsRow

class LabFeaturesActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var connectorEnabled by remember { mutableStateOf(ModuleSettings.isConnectorEnabled()) }
            var showUnsafeDialog by remember { mutableStateOf(false) }

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
                                    label = { WearText(text = stringResource(R.string.shizuku_connectors_title)) },
                                    secondaryLabel = { WearText(text = stringResource(R.string.shizuku_connectors_summary)) },
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
                            NightDogCard()
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
                }
            }
        }
    }
}

private fun bulldogDrawable(state: NightDogBulldogState): Int = when (state) {
    NightDogBulldogState.NORMAL -> R.drawable.nightdog_idle
    NightDogBulldogState.ALERT -> R.drawable.nightdog_alert
    NightDogBulldogState.RECOVERING -> R.drawable.nightdog_recovering
    NightDogBulldogState.SUCCESS -> R.drawable.nightdog_success
    NightDogBulldogState.ERROR -> R.drawable.nightdog_error
}

@Composable
private fun NightDogCard() {
    val state by NightDogManager.uiState.collectAsStateWithLifecycle()
    var showKillConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { NightDogManager.refreshStatus() }

    val available = NightDogManager.isRecoveryTestAvailable()
    val busy = state.isAnyBusy

    val modeText = when (state.mode) {
        ShizukuSettings.LaunchMethod.ADB -> stringResource(R.string.nightdog_mode_adb)
        ShizukuSettings.LaunchMethod.ROOT -> stringResource(R.string.nightdog_mode_root)
        else -> stringResource(R.string.nightdog_mode_unknown)
    }
    val lastRecoveryText = if (state.lastRecoveryAt <= 0L) {
        stringResource(R.string.nightdog_last_recovery_never)
    } else {
        DateUtils.getRelativeTimeSpanString(
            state.lastRecoveryAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    SettingsGroup(title = stringResource(R.string.nightdog_card_title)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(bulldogDrawable(state.bulldogState)),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (state.serverOnline) {
                            stringResource(R.string.nightdog_server_online)
                        } else {
                            stringResource(R.string.nightdog_server_offline)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = state.serverPid?.let { stringResource(R.string.nightdog_pid, it.toString()) }
                            ?: stringResource(R.string.nightdog_pid_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.nightdog_mode, modeText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.nightdog_recoveries, state.recoveryCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.nightdog_last_recovery, lastRecoveryText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            NightDogOperationSection(state)
            NightDogTestSection(state)

            Button(
                onClick = {
                    if (NightDogManager.requestTestConfirmation()) {
                        showKillConfirm = true
                    }
                },
                enabled = available && state.serverOnline && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.nightdog_test_button))
            }
            Text(
                text = stringResource(R.string.nightdog_test_experimental),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            if (!available) {
                Text(
                    text = stringResource(R.string.nightdog_test_unavailable_mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { showResetConfirm = true },
                enabled = !busy
            ) {
                Text(stringResource(R.string.nightdog_reset_stats))
            }
        }
    }

    if (showKillConfirm) {
        AlertDialog(
            onDismissRequest = { showKillConfirm = false },
            title = { Text(stringResource(R.string.nightdog_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.nightdog_confirm_message,
                        (state.testOldPid ?: state.serverPid)?.toString() ?: "—"
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKillConfirm = false
                    NightDogManager.confirmKillAndObserve()
                }) { Text(stringResource(R.string.nightdog_confirm_kill)) }
            },
            dismissButton = {
                TextButton(onClick = { showKillConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.nightdog_reset_confirm_title)) },
            text = { Text(stringResource(R.string.nightdog_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    NightDogManager.resetStats()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun NightDogOperationSection(state: moe.shizuku.manager.nightdog.NightDogUiState) {
    if (state.phase == NightDogPhase.IDLE) return

    val triggerText = when (state.trigger) {
        NightDogTrigger.APP_START -> stringResource(R.string.nightdog_trigger_app_start)
        NightDogTrigger.SYSTEM_BOOT -> stringResource(R.string.nightdog_trigger_system_boot)
        NightDogTrigger.AUTO_RECOVERY -> stringResource(R.string.nightdog_trigger_auto_recovery)
        NightDogTrigger.MANUAL_START -> stringResource(R.string.nightdog_trigger_manual_start)
        NightDogTrigger.MANUAL_TEST -> stringResource(R.string.nightdog_trigger_manual_test)
        null -> null
    }

    val phaseText = when (state.phase) {
        NightDogPhase.CHECKING -> stringResource(R.string.nightdog_op_checking)
        NightDogPhase.STARTING -> stringResource(R.string.nightdog_op_starting)
        NightDogPhase.WAITING_ADB -> when (state.progressMessage) {
            "adb_not_found" -> stringResource(R.string.nightdog_op_adb_not_found)
            else -> stringResource(R.string.nightdog_op_waiting_adb)
        }
        NightDogPhase.WAITING_SERVER -> stringResource(R.string.nightdog_op_waiting_server)
        NightDogPhase.RECOVERING -> stringResource(R.string.nightdog_op_recovering)
        NightDogPhase.SUCCESS -> when (state.progressMessage) {
            "recovered" -> stringResource(R.string.nightdog_op_recovered)
            else -> stringResource(R.string.nightdog_op_success)
        }
        NightDogPhase.ERROR -> state.errorMessage ?: stringResource(R.string.nightdog_op_error_sdk)
        NightDogPhase.IDLE -> ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.isOperationInProgress) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column {
                    Text(
                        text = phaseText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    triggerText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.attempt > 1) {
                        Text(
                            text = stringResource(R.string.nightdog_attempt, state.attempt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (state.phase == NightDogPhase.SUCCESS) {
            Text(
                text = phaseText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            triggerText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.newPid?.let {
                Text(
                    text = stringResource(R.string.nightdog_pid, it.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (state.phase == NightDogPhase.ERROR) {
            Text(
                text = phaseText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            triggerText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NightDogTestSection(state: moe.shizuku.manager.nightdog.NightDogUiState) {
    when (state.testPhase) {
        NightDogTestPhase.IDLE, NightDogTestPhase.CONFIRMING -> Unit
        NightDogTestPhase.PREPARING,
        NightDogTestPhase.STOPPING,
        NightDogTestPhase.SERVER_DOWN,
        NightDogTestPhase.WAITING_NIGHTDOG,
        NightDogTestPhase.VERIFYING -> {
            val label = when (state.testPhase) {
                NightDogTestPhase.PREPARING -> stringResource(R.string.nightdog_phase_preparing)
                NightDogTestPhase.STOPPING -> stringResource(R.string.nightdog_phase_stopping)
                NightDogTestPhase.SERVER_DOWN -> stringResource(R.string.nightdog_phase_server_down)
                NightDogTestPhase.WAITING_NIGHTDOG -> stringResource(R.string.nightdog_phase_waiting)
                else -> stringResource(R.string.nightdog_phase_verifying)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        NightDogTestPhase.RECOVERED -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.nightdog_result_success_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.nightdog_result_old_pid, state.testOldPid?.toString() ?: "—"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.nightdog_result_new_pid, state.testNewPid?.toString() ?: "—"),
                    style = MaterialTheme.typography.bodyMedium
                )
                state.testElapsedMs?.let {
                    Text(
                        text = stringResource(R.string.nightdog_result_time, formatSeconds(it)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = { NightDogManager.dismissTestResult() }) {
                    Text(stringResource(R.string.nightdog_dismiss))
                }
            }
        }
        NightDogTestPhase.ERROR, NightDogTestPhase.UNAVAILABLE -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.nightdog_result_fail_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = when (state.testMessage) {
                        "timeout" -> stringResource(R.string.nightdog_result_fail_timeout)
                        "not_killed" -> stringResource(R.string.nightdog_result_fail_notkilled)
                        "verify_failed" -> stringResource(R.string.nightdog_result_fail_verify)
                        "pid_unresolved" -> stringResource(R.string.nightdog_result_fail_pid)
                        "unavailable_mode" -> stringResource(R.string.nightdog_test_unavailable_mode)
                        "server_offline" -> stringResource(R.string.nightdog_test_unavailable_offline)
                        else -> stringResource(R.string.nightdog_result_fail_generic)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                state.testOldPid?.let {
                    Text(
                        text = stringResource(R.string.nightdog_result_old_pid, it.toString()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = { NightDogManager.dismissTestResult() }) {
                    Text(stringResource(R.string.nightdog_dismiss))
                }
            }
        }
    }
}

private fun formatSeconds(ms: Long): String {
    val seconds = ms / 1000.0
    return "${(seconds * 10).toLong() / 10.0} s"
}
