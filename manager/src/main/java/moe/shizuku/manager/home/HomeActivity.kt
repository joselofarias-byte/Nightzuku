@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package moe.shizuku.manager.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.module.AdbModuleManager
import moe.shizuku.manager.module.ModulesActivity
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellTutorialActivity
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.core.util.ClipboardUtils
import rikka.lifecycle.Resource
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

private const val NIGHTZUKU_BRAND = "JoseloFarias"
private const val NIGHTZUKU_UPSTREAM = "kerneldroid/Nightzuku · RikkaApps/Shizuku"

abstract class HomeActivity : AppActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AdbModuleManager.resetServiceRunGuard()
    }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val permissionRefreshTick = mutableIntStateOf(0)
    private var isInitialResume = true
    private var pendingLocalNetworkAction: (() -> Unit)? = null

    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTick.intValue++
        val action = pendingLocalNetworkAction
        pendingLocalNetworkAction = null
        if (granted) action?.invoke()
        else Toast.makeText(this, R.string.home_local_network_permission_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val serviceResource by homeModel.serviceStatus.observeAsState()
            val runtimeStatus by homeModel.runtimeStatus.collectAsStateWithLifecycle()
            val grantedResource by appsModel.grantedCount.observeAsState()
            val unauthorizedResource by appsModel.unauthorizedCount.observeAsState()
            val localNetworkPermissionState = remember(permissionRefreshTick.intValue) {
                buildLocalNetworkPermissionState()
            }

            LaunchedEffect(runtimeStatus) { homeModel.reload() }

            LaunchedEffect(serviceResource?.status, serviceResource?.data?.uid) {
                val status = serviceResource?.data ?: return@LaunchedEffect
                if (serviceResource?.status == Status.SUCCESS && status.isRunning) {
                    ShizukuSettings.setLastLaunchMode(
                        if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT
                        else ShizukuSettings.LaunchMethod.ADB
                    )
                    runCatching { AdbModuleManager.runEnabledServicesIfAllowed(applicationContext) }
                }
            }

            var showAboutDialog by remember { mutableStateOf(false) }
            var showStopDialog by remember { mutableStateOf(false) }
            var showAdbCommandDialog by remember { mutableStateOf(false) }
            var showAdbDiscoveryDialog by remember { mutableStateOf(false) }
            var showWadbNotEnabledDialog by remember { mutableStateOf(false) }
            var showAdbPairDialog by remember { mutableStateOf(false) }

            ShizukuExpressiveTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        serviceResource = serviceResource,
                        grantedResource = grantedResource,
                        unauthorizedResource = unauthorizedResource,
                        localNetworkPermissionState = localNetworkPermissionState,
                        lastChecked = runtimeStatus.lastChecked,
                        isPrimaryUser = UserHandleCompat.myUserId() == 0,
                        isRooted = EnvironmentUtils.isRooted(),
                        onRefresh = { checkServerStatus(); appsModel.load() },
                        onSettings = { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) },
                        onAbout = { showAboutDialog = true },
                        onStop = { showStopDialog = true },
                        onModules = { startActivity(Intent(this@HomeActivity, ModulesActivity::class.java)) },
                        onManageApps = { startActivity(Intent(this@HomeActivity, ApplicationManagementActivity::class.java)) },
                        onTerminal = { startActivity(Intent(this@HomeActivity, ShellTutorialActivity::class.java)) },
                        onStartRoot = ::startRoot,
                        onStartWirelessAdb = {
                            runWithLocalNetworkAccess {
                                startWirelessAdb(
                                    onShowDiscovery = { showAdbDiscoveryDialog = true },
                                    onShowNotEnabled = { showWadbNotEnabledDialog = true }
                                )
                            }
                        },
                        onPairWirelessAdb = {
                            runWithLocalNetworkAccess {
                                pairWirelessAdb(onShowPair = { showAdbPairDialog = true })
                            }
                        },
                        onOpenWirelessGuide = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_ANDROID11.get()) },
                        onShowAdbCommand = { showAdbCommandDialog = true },
                        onOpenAdbHelp = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB.get()) },
                        onOpenAdbPermissionHelp = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.ADB_PERMISSION.get()) },
                        onLearnMore = { CustomTabsHelper.launchUrlOrCopy(this@HomeActivity, Helps.HOME.get()) },
                        onCopyDiagnostics = { copyDiagnostics(it) },
                        onShareDiagnostics = { shareDiagnostics(it) },
                        onRequestLocalNetworkPermission = { requestLocalNetworkPermission { permissionRefreshTick.intValue++ } }
                    )

                    if (showAboutDialog) {
                        HomeAboutDialog(
                            onDismiss = { showAboutDialog = false },
                            onSourceCode = {
                                CustomTabsHelper.launchUrlOrCopy(
                                    this@HomeActivity,
                                    "https://github.com/joselofarias-byte/Nightzuku"
                                )
                            }
                        )
                    }
                    if (showStopDialog) {
                        HomeStopDialog(
                            onDismiss = { showStopDialog = false },
                            onConfirm = { runCatching { Shizuku.exit() } }
                        )
                    }
                    if (showAdbCommandDialog) {
                        HomeAdbCommandDialog(
                            command = Starter.adbCommand,
                            onDismiss = { showAdbCommandDialog = false },
                            onCopy = {
                                if (ClipboardUtils.put(this@HomeActivity, Starter.adbCommand)) {
                                    Toast.makeText(
                                        this@HomeActivity,
                                        getString(R.string.toast_copied_to_clipboard, Starter.adbCommand),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onSend = {
                                var intent = Intent(Intent.ACTION_SEND)
                                intent.type = "text/plain"
                                intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                                intent = Intent.createChooser(intent, getString(R.string.home_adb_dialog_view_command_button_send))
                                startActivity(intent)
                            }
                        )
                    }
                    if (showAdbDiscoveryDialog) {
                        HomeAdbDiscoveryDialog(
                            onDismiss = { showAdbDiscoveryDialog = false },
                            onStart = { port -> startAndDismiss(port); showAdbDiscoveryDialog = false }
                        )
                    }
                    if (showWadbNotEnabledDialog) {
                        HomeWadbNotEnabledDialog(onDismiss = { showWadbNotEnabledDialog = false })
                    }
                    if (showAdbPairDialog) {
                        HomeAdbPairDialog(onDismiss = { showAdbPairDialog = false })
                    }
                }
            }
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    private fun startAndDismiss(port: Int) {
        startActivity(Intent(this, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, false)
            putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
            putExtra(StarterActivity.EXTRA_PORT, port)
        })
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
        if (isInitialResume) isInitialResume = false else appsModel.load(onlyCount = true)
        permissionRefreshTick.intValue++
    }

    private fun checkServerStatus() { homeModel.reload() }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        return false
    }

    private fun startRoot() {
        if (!EnvironmentUtils.isRooted()) return
        startActivity(Intent(this, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, true)
        })
    }

    private fun startWirelessAdb(onShowDiscovery: () -> Unit, onShowNotEnabled: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            onShowDiscovery()
            return
        }
        val port = EnvironmentUtils.getAdbTcpPort()
        if (port > 0) {
            startActivity(Intent(this, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                putExtra(StarterActivity.EXTRA_PORT, port)
            })
        } else onShowNotEnabled()
    }

    private fun pairWirelessAdb(onShowPair: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val isWatch = EnvironmentUtils.isWatch(this)
        if (isWatch || (display?.displayId ?: -1) > 0 || isInMultiWindowMode) onShowPair()
        else startActivity(Intent(this, moe.shizuku.manager.adb.AdbPairingTutorialActivity::class.java))
    }

    private fun runWithLocalNetworkAccess(action: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) { action(); return }
        pendingLocalNetworkAction = action
        localNetworkPermissionLauncher.launch(state.permission!!)
    }

    private fun requestLocalNetworkPermission(onGranted: () -> Unit) {
        val state = buildLocalNetworkPermissionState()
        if (!state.required || state.granted) { onGranted(); return }
        pendingLocalNetworkAction = onGranted
        localNetworkPermissionLauncher.launch(state.permission!!)
    }

    private fun buildLocalNetworkPermissionState(): LocalNetworkPermissionState {
        val permission = when {
            Build.VERSION.SDK_INT >= SDK_ANDROID_17 -> PERMISSION_ACCESS_LOCAL_NETWORK
            Build.VERSION.SDK_INT >= SDK_ANDROID_16 -> Manifest.permission.NEARBY_WIFI_DEVICES
            else -> null
        }
        return LocalNetworkPermissionState(
            permission = permission,
            required = permission != null,
            granted = permission == null || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun copyDiagnostics(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.home_diagnostics_title), text))
        Toast.makeText(this, R.string.home_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        startActivity(Intent.createChooser(intent, getString(R.string.home_diagnostics_share)))
    }

    companion object {
        private const val SDK_ANDROID_16 = 36
        private const val SDK_ANDROID_17 = 37
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

internal data class LocalNetworkPermissionState(
    val permission: String?,
    val required: Boolean,
    val granted: Boolean
) {
    val label: String get() = permission?.substringAfterLast('.') ?: "none"
}

private data class HomeButtonSpec(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun HomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    unauthorizedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    lastChecked: Long,
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
    onShareDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val context = LocalContext.current
    val isWatch = remember(context) { EnvironmentUtils.isWatch(context) }
    val isTv = remember(context) { EnvironmentUtils.isTV(context) }
    if (isWatch) {
        moe.shizuku.manager.ui.compose.WearShizukuTheme {
            WearHomeScreen(
                serviceResource, grantedResource, localNetworkPermissionState, lastChecked,
                isPrimaryUser, isRooted, onRefresh, onSettings, onAbout, onStop, onModules,
                onManageApps, onTerminal, onStartRoot, onStartWirelessAdb, onPairWirelessAdb,
                onOpenWirelessGuide, onShowAdbCommand, onOpenAdbHelp, onOpenAdbPermissionHelp,
                onLearnMore, onCopyDiagnostics, onRequestLocalNetworkPermission
            )
        }
    } else if (isTv) {
        moe.shizuku.manager.ui.compose.TvShizukuTheme {
            TVHomeScreen(
                serviceResource, grantedResource, unauthorizedResource, localNetworkPermissionState,
                lastChecked, isPrimaryUser, isRooted, onRefresh, onSettings, onAbout, onStop,
                onModules, onManageApps, onTerminal, onStartRoot, onStartWirelessAdb,
                onPairWirelessAdb, onOpenWirelessGuide, onShowAdbCommand, onOpenAdbHelp,
                onOpenAdbPermissionHelp, onLearnMore, onCopyDiagnostics, onRequestLocalNetworkPermission
            )
        }
    } else {
        PhoneHomeScreen(
            serviceResource, grantedResource, unauthorizedResource, localNetworkPermissionState,
            lastChecked, isPrimaryUser, isRooted, onRefresh, onSettings, onAbout, onStop,
            onModules, onManageApps, onTerminal, onStartRoot, onStartWirelessAdb,
            onPairWirelessAdb, onOpenWirelessGuide, onShowAdbCommand, onOpenAdbHelp,
            onOpenAdbPermissionHelp, onLearnMore, onCopyDiagnostics, onShareDiagnostics,
            onRequestLocalNetworkPermission
        )
    }
}

@Composable
private fun PhoneHomeScreen(
    serviceResource: Resource<ServiceStatus>?,
    grantedResource: Resource<Int>?,
    unauthorizedResource: Resource<Int>?,
    localNetworkPermissionState: LocalNetworkPermissionState,
    lastChecked: Long,
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
    onShareDiagnostics: (String) -> Unit,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val context = LocalContext.current
    val status = serviceResource?.data ?: ServiceStatus()
    val grantedCount = grantedResource?.data ?: 0
    val running = status.isRunning
    val adbPermission = status.permission
    val recoverySnapshot by NightDogRecovery.snapshot.collectAsStateWithLifecycle()
    val canUseWirelessAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0
    var moreOpen by remember { mutableStateOf(false) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(running, recoverySnapshot.runningSinceElapsedRealtime) {
        elapsedNow = SystemClock.elapsedRealtime()
        while (running) { delay(1_000L); elapsedNow = SystemClock.elapsedRealtime() }
    }

    val diagnostics = remember(status, grantedCount, localNetworkPermissionState, lastChecked, recoverySnapshot, elapsedNow) {
        buildDiagnostics(context, status, grantedCount, localNetworkPermissionState, lastChecked, recoverySnapshot, elapsedNow)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            NIGHTZUKU_BRAND,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { ShizukuIcon(R.drawable.ic_action_settings_24dp, stringResource(R.string.settings_title)) }
                    IconButton(onClick = onRefresh) { ShizukuIcon(R.drawable.ic_server_restart, stringResource(R.string.home_refresh)) }
                    Box {
                        IconButton(onClick = { moreOpen = true }) { ShizukuIcon(R.drawable.ic_more_vert_24, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (!running) {
                                if (isRooted) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_root_button_start)) },
                                        leadingIcon = { ShizukuIcon(R.drawable.ic_server_start_24dp, null) },
                                        onClick = { moreOpen = false; onStartRoot() }
                                    )
                                } else if (canUseWirelessAdb) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.home_root_button_start)) },
                                        leadingIcon = { ShizukuIcon(R.drawable.ic_server_start_24dp, null) },
                                        onClick = { moreOpen = false; onStartWirelessAdb() }
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_about)) },
                                leadingIcon = { ShizukuIcon(R.drawable.ic_outline_info_24, null) },
                                onClick = { moreOpen = false; onAbout() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatusCard(serviceResource, status, onStartRoot, onStartWirelessAdb, isRooted, canUseWirelessAdb)
            }
            if (adbPermission) {
                item { ManageAppsCard(status, grantedResource, unauthorizedResource, onManageApps) }
                item {
                    SimpleActionCard(
                        R.drawable.ic_adb_24dp,
                        stringResource(R.string.modules_title),
                        if (running) stringResource(R.string.home_modules_description)
                        else stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name)),
                        running,
                        onModules
                    )
                }
                item {
                    SimpleActionCard(
                        R.drawable.ic_terminal_24,
                        stringResource(R.string.home_terminal_title),
                        if (running) stringResource(R.string.home_terminal_description)
                        else stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name)),
                        running,
                        onTerminal
                    )
                }
            }
            if (running && !adbPermission) {
                item {
                    HomeCard(
                        R.drawable.ic_warning_24,
                        stringResource(R.string.home_adb_is_limited_title),
                        stringResource(R.string.home_adb_is_limited_description),
                        collapsedBody = "Permiso ADB limitado.",
                        expandable = true
                    ) {
                        HomeButtons(listOf(HomeButtonSpec(R.string.home_adb_button_view_help, R.drawable.ic_help_outline_24dp, true, onClick = onOpenAdbPermissionHelp)))
                    }
                }
            }
            if (isPrimaryUser) {
                val rootRestart = running && status.uid == 0
                if (isRooted) item { RootCard(rootRestart, onStartRoot) }
                if (canUseWirelessAdb) {
                    item { WirelessAdbCard(running, localNetworkPermissionState, onStartWirelessAdb, onPairWirelessAdb, onOpenWirelessGuide) }
                }
                item { AdbCommandCard(onShowAdbCommand, onOpenAdbHelp) }
            }
            if (localNetworkPermissionState.required && !localNetworkPermissionState.granted) {
                item { LocalNetworkPermissionCard(localNetworkPermissionState, onRequestLocalNetworkPermission) }
            }
            item { DiagnosticsCard(diagnostics, onCopyDiagnostics, onShareDiagnostics) }
            item {
                SimpleActionCard(
                    R.drawable.ic_learn_more_24dp,
                    stringResource(R.string.home_learn_more_title),
                    stringResource(R.string.home_learn_more_description),
                    onClick = onLearnMore
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceResource: Resource<ServiceStatus>?,
    status: ServiceStatus,
    onStartRoot: () -> Unit,
    onStartWirelessAdb: () -> Unit,
    isRooted: Boolean,
    canUseWirelessAdb: Boolean
) {
    val context = LocalContext.current
    val recoverySnapshot by NightDogRecovery.snapshot.collectAsStateWithLifecycle()
    val running = status.isRunning
    val desiredRunning = recoverySnapshot.desiredRunning
    val isLoading = serviceResource == null || serviceResource.status == Status.LOADING
    val isError = serviceResource?.status == Status.ERROR || recoverySnapshot.stage == NightDogRecovery.Stage.ERROR
    val recovering = !running && desiredRunning && !isError
    val title = when {
        running -> stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
        isError -> stringResource(R.string.notification_service_start_failed)
        !desiredRunning || recoverySnapshot.stage == NightDogRecovery.Stage.MANUALLY_STOPPED -> stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
        else -> stringResource(R.string.home_status_checking)
    }
    val statusArtwork = when {
        running -> R.drawable.nightdog_status_active
        isError -> R.drawable.nightdog_status_error
        recovering -> R.drawable.nightdog_status_recovering
        else -> R.drawable.nightdog_status_stopped
    }
    val summary = remember(status, running, recoverySnapshot) {
        if (running) buildServiceSummary(context, status) else buildRecoverySummary(recoverySnapshot)
    }
    val dark = isSystemInDarkTheme()
    val semanticColor = when {
        running -> if (dark) Color(0xFF81C784) else Color(0xFF1B5E20)
        isError -> if (dark) Color(0xFFFF8A80) else MaterialTheme.colorScheme.error
        recovering || isLoading -> if (dark) Color(0xFFFFD54F) else Color(0xFF8A5A00)
        else -> if (dark) Color(0xFFBDBDBD) else Color(0xFF616161)
    }

    HomeCard(
        icon = if (running) R.drawable.ic_server_ok_24dp else if (isLoading) R.drawable.ic_server_restart else R.drawable.ic_server_error_24dp,
        title = title,
        body = summary,
        artwork = statusArtwork,
        titleColor = semanticColor,
        bodyColor = semanticColor.copy(alpha = 0.82f)
    ) {
        if (isLoading && !recovering) {
            Spacer(Modifier.height(12.dp)); LoadingIndicator(Modifier.size(32.dp))
        } else if (!running && !desiredRunning) {
            val button = when {
                isRooted -> HomeButtonSpec(R.string.home_root_button_start, R.drawable.ic_server_start_24dp, true, onClick = onStartRoot)
                canUseWirelessAdb -> HomeButtonSpec(R.string.home_root_button_start, R.drawable.ic_server_start_24dp, true, onClick = onStartWirelessAdb)
                else -> null
            }
            button?.let { HomeButtons(listOf(it)) }
        }
    }
}

@Composable
private fun ManageAppsCard(
    status: ServiceStatus,
    grantedResource: Resource<Int>?,
    unauthorizedResource: Resource<Int>?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val running = status.isRunning
    val showCount = running && grantedResource?.status == Status.SUCCESS && grantedResource.data != null && unauthorizedResource?.status == Status.SUCCESS && unauthorizedResource.data != null
    val title = if (showCount) {
        val count = grantedResource!!.data!!
        if (count == 0) stringResource(R.string.home_app_management_no_authorized_apps)
        else context.resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, count, count)
    } else stringResource(R.string.home_app_management_title)
    val body = if (showCount) {
        val count = unauthorizedResource!!.data!!
        if (count == 0) stringResource(R.string.home_app_management_no_unauthorized_apps)
        else context.resources.getQuantityString(R.plurals.home_app_management_unauthorized_apps_count, count, count)
    } else if (running) stringResource(R.string.home_app_management_view_authorized_apps)
    else stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
    SimpleActionCard(R.drawable.ic_system_icon, title, body, running, onClick)
}

@Composable
private fun RootCard(restart: Boolean, onStartRoot: () -> Unit) {
    if (!EnvironmentUtils.isRooted()) return
    val buttonLabel = if (restart) R.string.home_root_button_restart else R.string.home_root_button_start
    val buttonIcon = if (restart) R.drawable.ic_server_restart else R.drawable.ic_server_start_24dp
    HomeCard(
        R.drawable.ic_root_24dp,
        htmlStringResource(R.string.home_root_title),
        htmlStringResource(R.string.home_root_description, "Don't kill my app!"),
        collapsedBody = if (restart) "Reiniciar mediante root." else "Iniciar mediante root.",
        expandable = true
    ) {
        HomeButtons(listOf(HomeButtonSpec(buttonLabel, buttonIcon, true, onClick = onStartRoot)))
    }
}

@Composable
private fun WirelessAdbCard(
    running: Boolean,
    localNetworkPermissionState: LocalNetworkPermissionState,
    onStartWirelessAdb: () -> Unit,
    onPairWirelessAdb: () -> Unit,
    onOpenWirelessGuide: () -> Unit
) {
    val body = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) htmlStringResource(R.string.home_wireless_adb_description)
    else htmlStringResource(R.string.home_wireless_adb_description_pre_11)
    val permissionLine = if (localNetworkPermissionState.required) {
        val permissionLabel = if (localNetworkPermissionState.label == "NEARBY_WIFI_DEVICES") stringResource(R.string.permission_nearby_wifi_devices) else localNetworkPermissionState.label
        stringResource(if (localNetworkPermissionState.granted) R.string.home_local_network_granted else R.string.home_local_network_missing, permissionLabel)
    } else null
    HomeCard(
        R.drawable.ic_wadb_24,
        htmlStringResource(R.string.home_wireless_adb_title),
        listOfNotNull(body, permissionLine).joinToString("\n\n"),
        collapsedBody = if (running) "Depuración inalámbrica disponible." else "Iniciar sin computadora mediante depuración inalámbrica.",
        expandable = true
    ) {
        val buttons = mutableListOf<HomeButtonSpec>()
        if (!running) buttons += HomeButtonSpec(R.string.home_root_button_start, R.drawable.ic_server_start_24dp, true, onClick = onStartWirelessAdb)
        if (!running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            buttons += HomeButtonSpec(R.string.adb_pairing, R.drawable.ic_numeric_1_circle_outline_24, onClick = onPairWirelessAdb)
            buttons += HomeButtonSpec(R.string.home_wireless_adb_view_guide_button, R.drawable.ic_help_outline_24dp, onClick = onOpenWirelessGuide)
        }
        HomeButtons(buttons)
    }
}

@Composable
private fun AdbCommandCard(onShowAdbCommand: () -> Unit, onOpenAdbHelp: () -> Unit) {
    HomeCard(
        R.drawable.ic_adb_24dp,
        htmlStringResource(R.string.home_adb_title),
        htmlStringResource(R.string.home_adb_description, Helps.ADB.get()),
        collapsedBody = "Iniciar mediante ADB desde una computadora.",
        expandable = true
    ) {
        HomeButtons(listOf(
            HomeButtonSpec(R.string.home_adb_button_view_command, R.drawable.ic_code_24dp, true, onClick = onShowAdbCommand),
            HomeButtonSpec(R.string.home_adb_button_view_help, R.drawable.ic_help_outline_24dp, onClick = onOpenAdbHelp)
        ))
    }
}

@Composable
private fun LocalNetworkPermissionCard(
    localNetworkPermissionState: LocalNetworkPermissionState,
    onRequestLocalNetworkPermission: () -> Unit
) {
    val permissionLabel = if (localNetworkPermissionState.label == "NEARBY_WIFI_DEVICES") stringResource(R.string.permission_nearby_wifi_devices) else localNetworkPermissionState.label
    HomeCard(
        R.drawable.ic_warning_24,
        stringResource(R.string.home_local_network_title),
        stringResource(R.string.home_local_network_description, permissionLabel)
    ) {
        HomeButtons(listOf(HomeButtonSpec(R.string.home_local_network_grant, R.drawable.ic_settings_outline_24dp, true, onClick = onRequestLocalNetworkPermission)))
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: String,
    onCopyDiagnostics: (String) -> Unit,
    onShareDiagnostics: (String) -> Unit
) {
    val compactSummary = remember(diagnostics) { diagnostics.lineSequence().take(3).joinToString("\n") }
    HomeCard(
        R.drawable.ic_outline_info_24,
        stringResource(R.string.home_diagnostics_title),
        diagnostics,
        collapsedBody = compactSummary,
        expandable = true,
        bodyFontFamily = FontFamily.Monospace
    ) {
        HomeButtons(listOf(
            HomeButtonSpec(R.string.home_diagnostics_copy, R.drawable.ic_content_copy_24, true, onClick = { onCopyDiagnostics(diagnostics) }),
            HomeButtonSpec(R.string.home_diagnostics_share, R.drawable.ic_share_24dp, onClick = { onShareDiagnostics(diagnostics) })
        ))
    }
}

@Composable
private fun SimpleActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    HomeCard(icon, title, body, enabled = enabled, onClick = onClick)
}

@Composable
private fun HomeCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes artwork: Int? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    collapsedBody: String? = null,
    expandable: Boolean = false,
    bodyFontFamily: FontFamily? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val effectiveClick = when {
        onClick != null -> onClick
        expandable -> ({ expanded = !expanded })
        else -> null
    }
    val visibleBody = if (expandable && !expanded) collapsedBody ?: body.lineSequence().firstOrNull().orEmpty() else body
    val clickableModifier = if (effectiveClick != null) Modifier.clickable(enabled = enabled, onClick = effectiveClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickableModifier).alpha(if (enabled) 1f else 0.56f),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (artwork != null) {
                Image(painterResource(artwork), null, Modifier.size(88.dp), contentScale = ContentScale.Fit)
            } else {
                Surface(Modifier.size(44.dp), CircleShape, color = iconContainerColor) {
                    Box(contentAlignment = Alignment.Center) {
                        ShizukuIcon(icon, null, tint = iconContentColor, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = titleColor)
                if (visibleBody.isNotBlank()) {
                    Text(visibleBody, style = MaterialTheme.typography.bodyMedium, color = bodyColor, fontFamily = bodyFontFamily)
                }
                if (expandable) {
                    Text(
                        if (expanded) "Tocar para contraer" else "Tocar para ver detalles",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun HomeButtons(buttons: List<HomeButtonSpec>) {
    if (buttons.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { button ->
            if (button.primary) {
                Button(enabled = button.enabled, onClick = button.onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    ButtonIcon(button.icon); Text(stringResource(button.label))
                }
            } else if (button.enabled) {
                FilledTonalButton(onClick = button.onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    ButtonIcon(button.icon); Text(stringResource(button.label))
                }
            } else {
                OutlinedButton(enabled = false, onClick = button.onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    ButtonIcon(button.icon); Text(stringResource(button.label))
                }
            }
        }
    }
}

@Composable
private fun ButtonIcon(@DrawableRes icon: Int) {
    ShizukuIcon(icon, null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
}

@Composable
private fun htmlStringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val raw = stringResource(id, *formatArgs)
    return remember(raw) { HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() }
}

private fun buildServiceSummary(context: android.content.Context, status: ServiceStatus): String {
    if (!status.isRunning) return ""
    val user = if (status.uid == 0) "root" else "adb"
    val version = "${status.apiVersion}.${status.patchVersion}"
    val latestVersion = "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
    val raw = if (status.apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
        context.getString(R.string.home_status_service_version_update, user, version, latestVersion)
    } else context.getString(R.string.home_status_service_version, user, version)
    return HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
}

private fun buildRecoverySummary(snapshot: NightDogRecovery.Snapshot): String = when (snapshot.stage) {
    NightDogRecovery.Stage.CHECKING_BINDER -> "Comprobando el servicio."
    NightDogRecovery.Stage.DISCOVERING_ADB -> "Buscando conexión ADB mediante mDNS/TLS."
    NightDogRecovery.Stage.WAITING_FOR_ADB -> snapshot.lastResult
    NightDogRecovery.Stage.STARTING_SERVICE -> "Conexión ADB encontrada. Iniciando Nightzuku."
    NightDogRecovery.Stage.ERROR -> snapshot.lastResult
    NightDogRecovery.Stage.MANUALLY_STOPPED -> "Recuperación automática deshabilitada."
    else -> snapshot.lastResult
}

private fun buildDiagnostics(
    context: android.content.Context,
    status: ServiceStatus,
    grantedCount: Int,
    localNetworkPermissionState: LocalNetworkPermissionState,
    lastChecked: Long,
    recoverySnapshot: NightDogRecovery.Snapshot,
    nowElapsedRealtime: Long
): String {
    val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
    val localNetwork = if (localNetworkPermissionState.required) {
        val permissionLabel = if (localNetworkPermissionState.label == "NEARBY_WIFI_DEVICES") context.getString(R.string.permission_nearby_wifi_devices) else localNetworkPermissionState.label
        val statusLabel = if (localNetworkPermissionState.granted) context.getString(R.string.diagnostic_permission_granted) else context.getString(R.string.diagnostic_permission_missing)
        "$permissionLabel: $statusLabel"
    } else context.getString(R.string.diagnostic_permission_not_required)
    val serviceStatusLabel = if (status.isRunning) context.getString(R.string.diagnostic_running) else context.getString(R.string.diagnostic_stopped)
    val adbPermissionLabel = if (status.permission) context.getString(R.string.diagnostic_full) else context.getString(R.string.diagnostic_limited)
    val lastCheckedLabel = if (lastChecked > 0L) formatRelativeTime(context, lastChecked) else "—"
    val serverMode = when { status.uid == 0 -> "Root"; status.uid > 0 -> "Shell / ADB"; else -> "No disponible" }
    val securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: "no disponible"
    val desiredState = if (recoverySnapshot.desiredRunning) "ACTIVO" else "DETENIDO MANUALMENTE"
    val binderState = if (recoverySnapshot.binderAlive || status.isRunning) "OK" else "SIN RESPUESTA"
    val uptime = if (status.isRunning && recoverySnapshot.runningSinceElapsedRealtime > 0L) formatElapsedDuration(nowElapsedRealtime - recoverySnapshot.runningSinceElapsedRealtime) else "—"
    val lastLoss = formatElapsedAgo(nowElapsedRealtime, recoverySnapshot.lastBinderLostElapsedRealtime)
    val lastRecovery = formatElapsedAgo(nowElapsedRealtime, recoverySnapshot.lastRecoveryElapsedRealtime)
    val pid = recoverySnapshot.serverPid?.toString() ?: "resolviendo…"

    fun line(label: String, value: Any?): String = label.padEnd(22) + (value ?: "—")

    return buildString {
        appendLine("$serviceStatusLabel · $serverMode")
        appendLine("T+$uptime · PID $pid")
        appendLine("Versión $versionName (${BuildConfig.VERSION_CODE}) · API ${status.apiVersion}.${status.patchVersion}")
        appendLine()
        appendLine("SERVIDOR")
        appendLine(line("UID", status.uid))
        appendLine(line("SELinux", status.seContext ?: "desconocido"))
        appendLine(line("Permiso ADB", adbPermissionLabel))
        appendLine(line("Binder", binderState))
        appendLine(line("Transporte", recoverySnapshot.transport ?: "buscando"))
        appendLine(line("Dirección ADB", recoverySnapshot.endpoint ?: "no resuelta"))
        appendLine()
        appendLine("NIGHTDOG")
        appendLine(line("Deseado", desiredState))
        appendLine(line("Estado", recoveryStageLabel(recoverySnapshot.stage)))
        appendLine(line("Recuperaciones", recoverySnapshot.recoveryCount))
        appendLine(line("Última caída", lastLoss))
        appendLine(line("Última recuperación", lastRecovery))
        appendLine(line("Intentos fallidos", recoverySnapshot.failedAttempts))
        appendLine(line("Resultado", recoverySnapshot.lastResult))
        appendLine()
        appendLine("DISPOSITIVO")
        appendLine(line("Android", "${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}"))
        appendLine(line("Modelo", "${Build.MANUFACTURER} ${Build.MODEL}"))
        appendLine(line("ABI", Build.SUPPORTED_ABIS.joinToString()))
        appendLine(line("Parche", securityPatch))
        appendLine(line("Red local", localNetwork))
        appendLine(line("Apps autorizadas", grantedCount))
        appendLine()
        appendLine("EDICIÓN")
        appendLine(line("Marca", NIGHTZUKU_BRAND))
        appendLine(line("Origen", NIGHTZUKU_UPSTREAM))
        appendLine(line("Paquete", context.packageName))
        appendLine(line("Comprobación", lastCheckedLabel))
    }.trim()
}

private fun recoveryStageLabel(stage: NightDogRecovery.Stage): String = when (stage) {
    NightDogRecovery.Stage.CHECKING_BINDER -> "Comprobando"
    NightDogRecovery.Stage.DISCOVERING_ADB -> "Buscando ADB"
    NightDogRecovery.Stage.WAITING_FOR_ADB -> "Esperando ADB"
    NightDogRecovery.Stage.STARTING_SERVICE -> "Iniciando servicio"
    NightDogRecovery.Stage.ERROR -> "Error"
    NightDogRecovery.Stage.MANUALLY_STOPPED -> "Detenido manualmente"
    else -> stage.name.lowercase().replace('_', ' ')
}

private fun formatElapsedDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

private fun formatElapsedAgo(nowElapsedRealtime: Long, eventElapsedRealtime: Long): String {
    if (eventElapsedRealtime <= 0L) return "—"
    return "hace ${formatElapsedDuration(nowElapsedRealtime - eventElapsedRealtime)}"
}

private fun formatRelativeTime(context: android.content.Context, timeMillis: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        timeMillis,
        System.currentTimeMillis(),
        android.text.format.DateUtils.SECOND_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
