package moe.shizuku.manager.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import moe.shizuku.manager.R
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.ui.compose.WearScreenScaffold
import moe.shizuku.manager.ui.compose.WearScreenTitle
import moe.shizuku.manager.utils.EnvironmentUtils
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import rikka.lifecycle.Resource
import rikka.lifecycle.Status

@Composable
internal fun WearHomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onStop: () -> Unit,
    onModules: () -> Unit,
    onManageApps: () -> Unit,
    onTerminal: () -> Unit,
    onStartRoot: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit,
    onShowAdbCommand: () -> Unit,
    onOpenAdbHelp: () -> Unit,
    onOpenAdbPermissionHelp: () -> Unit,
    onLearnMore: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val status = serviceResource?.data ?: ServiceStatus()
    val running = status.isRunning
    val isLoading = serviceResource == null || serviceResource.status == Status.LOADING
    val canUseWirelessAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0

    WearScreenScaffold { state ->
        TransformingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                WearScreenTitle(icon = Icons.Rounded.PlayArrow, title = stringResource(R.string.app_name))
            }

            item {
                val dark = isSystemInDarkTheme()
                val (containerColor, contentColor) = when {
                    serviceResource == null || serviceResource.status == Status.LOADING -> {
                        if (dark) {
                            Color(0xFF4D3800) to Color(0xFFFFD54F)
                        } else {
                            Color(0xFFFFF0C2) to Color(0xFF6B4B00)
                        }
                    }
                    serviceResource.status == Status.ERROR -> {
                        if (dark) {
                            Color(0xFF5A1D1D) to Color(0xFFFFB4AB)
                        } else {
                            Color(0xFFFFDAD6) to Color(0xFF410002)
                        }
                    }
                    running -> {
                        if (dark) {
                            Color(0xFF0F3816) to Color(0xFF8CE090)
                        } else {
                            Color(0xFFC7F3C9) to Color(0xFF0F521A)
                        }
                    }
                    else -> {
                        if (dark) {
                            Color(0xFF333333) to Color(0xFFB0B0B0)
                        } else {
                            Color(0xFFE0E0E0) to Color(0xFF555555)
                        }
                    }
                }

                WearCard(
                    onClick = {},
                    colors = WearCardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        WearText(
                            text = when {
                                isLoading -> stringResource(R.string.home_status_checking)
                                running -> stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
                                else -> stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                            },
                            style = WearMaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (running) {
                item {
                    WearButton(
                        onClick = onManageApps,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.home_app_management_title))
                        }
                    }
                }
                item {
                    WearButton(
                        onClick = onModules,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Extension, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.modules_title))
                        }
                    }
                }
            } else {
                if (isRooted) {
                    item {
                        WearButton(
                            onClick = onStartRoot,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WearIcon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                WearText(text = stringResource(R.string.home_root_button_start))
                            }
                        }
                    }
                }
                if (canUseWirelessAdb) {
                    item {
                        WearButton(
                            onClick = onStartWirelessAdb,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WearIcon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                WearText(text = stringResource(R.string.home_wireless_adb_title))
                            }
                        }
                    }
                }
            }

            item {
                WearButton(
                    onClick = onSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.settings_title))
                    }
                }
            }

            item {
                WearButton(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.home_refresh))
                    }
                }
            }

            item {
                WearButton(
                    onClick = onAbout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WearIcon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        WearText(text = stringResource(R.string.action_about))
                    }
                }
            }

            if (running) {
                item {
                    WearButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WearIcon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            WearText(text = stringResource(R.string.action_stop))
                        }
                    }
                }
            }
        }
    }
}
