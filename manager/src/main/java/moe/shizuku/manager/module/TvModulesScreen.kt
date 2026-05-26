@file:OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package moe.shizuku.manager.module

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.SurfaceDefaults as TvSurfaceDefaults
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.compose.ShizukuIcon

@Composable
fun TvModulesScreen(
    modules: List<AdbModule>,
    onNavigateUp: () -> Unit,
    onInstallZip: () -> Unit,
    onToggle: (AdbModule) -> Unit,
    onRunAction: (AdbModule) -> Unit,
    onRunService: (AdbModule) -> Unit,
    onDelete: (AdbModule) -> Unit,
    onTrustChange: (AdbModule, Boolean) -> Unit,
    onOpenWebUi: (AdbModule) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .padding(32.dp)
                .size(width = 300.dp, height = 500.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvText(
                text = stringResource(R.string.modules_title),
                style = TvMaterialTheme.typography.headlineMedium,
                color = TvMaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            TvMenuButton(
                icon = R.drawable.ic_arrow_back_24,
                label = android.R.string.cancel,
                onClick = onNavigateUp
            )

            TvMenuButton(
                icon = R.drawable.ic_outline_arrow_upward_24,
                label = R.string.modules_install_zip,
                onClick = onInstallZip
            )
        }


        if (modules.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                TvText(
                    text = stringResource(R.string.modules_empty_title),
                    style = TvMaterialTheme.typography.headlineSmall,
                    color = TvMaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(modules) { module ->
                    val isTrusted = ModuleSettings.isModuleTrusted(module.id)
                    TvModuleCard(
                        module = module,
                        isTrusted = isTrusted,
                        onToggle = { onToggle(module) },
                        onRunAction = { onRunAction(module) },
                        onRunService = { onRunService(module) },
                        onDelete = { onDelete(module) },
                        onTrustChange = { onTrustChange(module, it) },
                        onOpenWebUi = { onOpenWebUi(module) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvMenuButton(
    icon: Int,
    label: Int,
    onClick: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
            focusedContainerColor = TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TvModuleCard(
    module: AdbModule,
    isTrusted: Boolean,
    onToggle: () -> Unit,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onDelete: () -> Unit,
    onTrustChange: (Boolean) -> Unit,
    onOpenWebUi: () -> Unit
) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = TvMaterialTheme.shapes.large,
        colors = TvSurfaceDefaults.colors(
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TvText(
                        text = module.name,
                        style = TvMaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TvText(
                        text = "${module.author} • ${module.version}",
                        style = TvMaterialTheme.typography.bodySmall,
                        color = TvMaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvText(
                        text = if (module.enabled) "ACTIVE" else "DISABLED",
                        color = if (module.enabled) TvMaterialTheme.colorScheme.primary else TvMaterialTheme.colorScheme.onSurfaceVariant,
                        style = TvMaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    if (isTrusted) {
                        TvText(
                            text = "TRUSTED",
                            color = TvMaterialTheme.colorScheme.primary,
                            style = TvMaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            module.description?.let {
                TvText(
                    text = it,
                    style = TvMaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvSmallButton(
                    label = if (module.enabled) R.string.modules_disable else R.string.modules_enable,
                    icon = if (module.enabled) R.drawable.ic_close_24 else R.drawable.ic_server_ok_24dp,
                    onClick = onToggle
                )

                TvSmallButton(
                    label = if (isTrusted) R.string.modules_untrust else R.string.modules_trust,
                    imageVector = Icons.Rounded.Shield,
                    onClick = { onTrustChange(!isTrusted) }
                )

                if (module.hasAction) {
                    TvSmallButton(
                        label = R.string.modules_run_action,
                        icon = R.drawable.ic_outline_play_arrow_24,
                        onClick = onRunAction
                    )
                }
                if (module.hasService) {
                    TvSmallButton(
                        label = R.string.modules_run_service,
                        icon = R.drawable.ic_terminal_24,
                        onClick = onRunService
                    )
                }
                if (module.hasWebUi) {
                    TvSmallButton(
                        label = R.string.modules_open_webui,
                        icon = R.drawable.ic_outline_open_in_new_24,
                        onClick = onOpenWebUi
                    )
                }

                TvSmallButton(
                    label = R.string.modules_delete,
                    icon = R.drawable.ic_close_24,
                    onClick = onDelete,
                    isDanger = true
                )
            }
        }
    }
}

@Composable
private fun TvSmallButton(
    @androidx.annotation.StringRes label: Int,
    @androidx.annotation.DrawableRes icon: Int? = null,
    imageVector: ImageVector? = null,
    onClick: () -> Unit,
    isDanger: Boolean = false
) {
    TvSurface(
        onClick = onClick,
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (isDanger) TvMaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            focusedContainerColor = if (isDanger) TvMaterialTheme.colorScheme.error else TvMaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (imageVector != null) {
                ShizukuIcon(imageVector = imageVector, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
                ShizukuIcon(icon = icon, modifier = Modifier.size(20.dp))
            }
            TvText(text = stringResource(label), style = TvMaterialTheme.typography.labelMedium)
        }
    }
}
