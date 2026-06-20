package moe.shizuku.manager.management

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.ShizukuScaffold
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.lifecycle.Status
import rikka.shizuku.Shizuku
import java.text.Collator
import java.util.Objects

class ApplicationManagementActivity : AppActivity() {

    private val viewModel by appsViewModel()
    private val permissionTick = mutableIntStateOf(0)

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        viewModel.packages.observe(this) {
            if (it.status == Status.ERROR) {
                finish()
                val tr = it.error
                Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                tr.printStackTrace()
            }
        }
        if (viewModel.packages.value == null) {
            viewModel.load()
        }

        Shizuku.addBinderDeadListener(binderDeadListener)

        setContent {
            val packagesResource by viewModel.packages.observeAsState()
            val packages = packagesResource?.data.orEmpty()
            val tick = permissionTick.intValue
            var showAdbLimitedDialog by remember { mutableStateOf(false) }

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ApplicationManagementActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@ApplicationManagementActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                val pm = LocalContext.current.packageManager
                val apps = androidx.compose.runtime.remember(packages, tick) {
                    packages.map { pkg ->
                        val appInfo = pkg.applicationInfo!!
                        WearAppItem(
                            label = appInfo.loadLabel(pm).toString(),
                            packageName = pkg.packageName,
                            uid = appInfo.uid,
                            granted = moe.shizuku.manager.authorization.AuthorizationManager.granted(pkg.packageName, appInfo.uid)
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    WearApplicationManagementScreen(
                        apps = apps,
                        onToggle = { app ->
                            try {
                                if (app.granted) {
                                    moe.shizuku.manager.authorization.AuthorizationManager.revoke(app.packageName, app.uid)
                                } else {
                                    moe.shizuku.manager.authorization.AuthorizationManager.grant(app.packageName, app.uid)
                                }
                                permissionTick.intValue++
                                viewModel.load(onlyCount = true)
                            } catch (_: SecurityException) {
                                showAdbLimitedDialog = true
                            }
                        }
                    )

                    if (showAdbLimitedDialog) {
                        moe.shizuku.manager.home.HomeAdbLimitedDialog(
                            onDismiss = { showAdbLimitedDialog = false }
                        )
                    }
                }

                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        TvApplicationManagementScreen(
                            packages = packages,
                            tick = tick,
                            onNavigateUp = { finish() },
                            onToggle = { pkg ->
                                val applicationInfo = pkg.applicationInfo ?: return@TvApplicationManagementScreen
                                try {
                                    if (AuthorizationManager.granted(pkg.packageName, applicationInfo.uid)) {
                                        AuthorizationManager.revoke(pkg.packageName, applicationInfo.uid)
                                    } else {
                                        AuthorizationManager.grant(pkg.packageName, applicationInfo.uid)
                                    }
                                    permissionTick.intValue++
                                    viewModel.load(onlyCount = true)
                                } catch (_: SecurityException) {
                                    showAdbLimitedDialog = true
                                }
                            },
                            onSelectAll = { selectAll(packages, it) }
                        )

                        if (showAdbLimitedDialog) {
                            moe.shizuku.manager.home.HomeAdbLimitedDialog(
                                onDismiss = { showAdbLimitedDialog = false }
                            )
                        }
                    }
                }
            } else {
                ShizukuExpressiveTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    var searchQuery by remember { mutableStateOf("") }
                    val pm = LocalContext.current.packageManager
                    val processedApps = remember(packages, tick, searchQuery) {
                        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
                        packages
                            .map { pkg ->
                                val appInfo = pkg.applicationInfo!!
                                val label = appInfo.loadLabel(pm).toString()
                                val uid = appInfo.uid
                                val userId = UserHandleCompat.getUserId(uid)
                                val title = if (userId != UserHandleCompat.myUserId()) {
                                    val userInfo = ShizukuSystemApis.getUserInfo(userId)
                                    "$label - ${userInfo.name} ($userId)"
                                } else {
                                    label
                                }
                                val granted = moe.shizuku.manager.authorization.AuthorizationManager.granted(pkg.packageName, uid)
                                AppDisplayInfo(pkg = pkg, title = title, granted = granted)
                            }
                            .filter { app ->
                                searchQuery.isEmpty() ||
                                app.title.contains(searchQuery, ignoreCase = true) ||
                                app.pkg.packageName.contains(searchQuery, ignoreCase = true)
                            }
                            .sortedWith { app1, app2 ->
                                if (app1.granted != app2.granted) {
                                    app2.granted.compareTo(app1.granted)
                                } else {
                                    collator.compare(app1.title, app2.title)
                                }
                            }
                    }

                    ShizukuScaffold(
                        title = stringResource(R.string.home_app_management_title),
                        onNavigateUp = { finish() },
                        actions = {
                            if (packages.isNotEmpty()) {
                                var menuExpanded by remember { mutableStateOf(false) }

                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        ShizukuIcon(R.drawable.ic_more_vert_24)
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.app_management_select_all)) },
                                            onClick = {
                                                menuExpanded = false
                                                selectAll(packages, true)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.app_management_deselect_all)) },
                                            onClick = {
                                                menuExpanded = false
                                                selectAll(packages, false)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (packages.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text(stringResource(R.string.app_management_search_placeholder)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = null
                                            )
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = "" }) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Close,
                                                        contentDescription = stringResource(R.string.app_management_search_clear)
                                                    )
                                                }
                                            }
                                        },
                                        shape = CircleShape,
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        )
                                    )
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars),
                                contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                when {
                                    packagesResource == null -> {
                                        item {
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                LoadingIndicator(Modifier.size(36.dp))
                                            }
                                        }
                                    }
                                    packages.isEmpty() -> {
                                        item {
                                            ExpressiveCard(
                                                icon = R.drawable.ic_system_icon,
                                                title = stringResource(R.string.home_app_management_title),
                                                body = stringResource(R.string.home_app_management_empty)
                                            )
                                        }
                                    }
                                    processedApps.isEmpty() -> {
                                        item {
                                            ExpressiveCard(
                                                icon = R.drawable.ic_system_icon,
                                                title = stringResource(R.string.app_management_search_empty),
                                                body = ""
                                            )
                                        }
                                    }
                                    else -> {
                                        item {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = MaterialTheme.shapes.extraLarge,
                                                color = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 1.dp
                                            ) {
                                                Column {
                                                    processedApps.forEachIndexed { index, appInfo ->
                                                        AppPermissionRow(
                                                            packageInfo = appInfo.pkg,
                                                            tick = tick,
                                                            onLimitedAdb = { showAdbLimitedDialog = true },
                                                            onPermissionChanged = {
                                                                permissionTick.intValue++
                                                                viewModel.load(onlyCount = true)
                                                            }
                                                        )
                                                        if (index != processedApps.lastIndex) {
                                                            HorizontalDivider(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                color = MaterialTheme.colorScheme.outlineVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showAdbLimitedDialog) {
                        moe.shizuku.manager.home.HomeAdbLimitedDialog(
                            onDismiss = { showAdbLimitedDialog = false }
                        )
                    }
                }
            }
            }
        }
    }

    private fun selectAll(packages: List<PackageInfo>, granted: Boolean) {
        packages.forEach { packageInfo ->
            val applicationInfo = packageInfo.applicationInfo ?: return@forEach
            val uid = applicationInfo.uid
            val packageName = packageInfo.packageName
            try {
                if (granted) {
                    AuthorizationManager.grant(packageName, uid)
                } else {
                    AuthorizationManager.revoke(packageName, uid)
                }
            } catch (_: SecurityException) {
            }
        }
        permissionTick.intValue++
        viewModel.load(onlyCount = true)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        permissionTick.intValue++
    }

    private fun showAdbLimitedDialog() {
    }
}

@Composable
private fun AppPermissionRow(
    packageInfo: PackageInfo,
    tick: Int,
    onLimitedAdb: () -> Unit,
    onPermissionChanged: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val applicationInfo = packageInfo.applicationInfo ?: return
    val uid = applicationInfo.uid
    val packageName = packageInfo.packageName
    var granted by remember(packageName, uid, tick) {
        mutableStateOf(AuthorizationManager.granted(packageName, uid))
    }
    val userId = UserHandleCompat.getUserId(uid)
    val title = remember(packageName, userId) {
        val label = applicationInfo.loadLabel(pm).toString()
        if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "$label - ${userInfo.name} ($userId)"
        } else {
            label
        }
    }
    val icon = remember(packageName) {
        applicationInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
    }
    val requiresRoot = applicationInfo.requiresRoot()

    fun toggle() {
        try {
            if (granted) {
                AuthorizationManager.revoke(packageName, uid)
            } else {
                AuthorizationManager.grant(packageName, uid)
            }
            granted = !granted
            onPermissionChanged()
        } catch (_: SecurityException) {
            val serverUid = try {
                Shizuku.getUid()
            } catch (_: Throwable) {
                return
            }
            if (serverUid != 0) {
                onLimitedAdb()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(46.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = packageInfo.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (requiresRoot) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(stringResource(R.string.app_management_item_summary_requires_root))
                    }
                )
            }
        }
        AppPermissionSwitch(
            checked = granted,
            onCheckedChange = { toggle() }
        )
    }
}

private fun ApplicationInfo.requiresRoot(): Boolean {
    return metaData?.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT") == true
}

private data class AppDisplayInfo(
    val pkg: PackageInfo,
    val title: String,
    val granted: Boolean
)

@Composable
private fun AppPermissionSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            if (checked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFFC8E6C9),
            checkedTrackColor = Color(0xFF2E7D32),
            checkedIconColor = Color(0xFF1B5E20),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = MaterialTheme.colorScheme.tertiary,
            uncheckedTrackColor = MaterialTheme.colorScheme.tertiaryContainer,
            uncheckedBorderColor = Color.Transparent,
            uncheckedIconColor = MaterialTheme.colorScheme.onTertiary,
            disabledCheckedThumbColor = Color(0xFFC8E6C9).copy(alpha = 0.38f),
            disabledCheckedTrackColor = Color(0xFF2E7D32).copy(alpha = 0.38f),
            disabledCheckedIconColor = Color(0xFF1B5E20).copy(alpha = 0.38f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.38f),
            disabledUncheckedTrackColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.38f),
            disabledUncheckedBorderColor = Color.Transparent,
            disabledUncheckedIconColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.38f)
        )
    )
}
