@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.wear.compose.material3.ExperimentalWearMaterial3Api::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog as WearAlertDialog
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.Text as WearText
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Text as TvText
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.EnvironmentUtils

@Composable
internal fun ForkAboutDialog(
    onDismiss: () -> Unit,
    onSourceCode: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val description = "Fork JoseloFarias\n$versionName\nBasado en kerneldroid/Nightzuku y RikkaApps/Shizuku"

    when {
        EnvironmentUtils.isWatch(context) -> {
            moe.shizuku.manager.ui.compose.WearShizukuTheme {
                WearAlertDialog(
                    show = true,
                    onDismissRequest = onDismiss,
                    title = { WearText(stringResource(R.string.app_name)) },
                    text = { WearText(description) }
                ) {
                    item {
                        WearButton(onClick = onSourceCode, modifier = Modifier.fillMaxWidth()) {
                            WearText("Código fuente del fork")
                        }
                    }
                    item {
                        WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            WearText(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
        EnvironmentUtils.isTV(context) -> {
            moe.shizuku.manager.ui.compose.TvShizukuTheme {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { TvText(stringResource(R.string.app_name)) },
                    text = { TvText(description) },
                    confirmButton = {
                        TvButton(onClick = onSourceCode) { TvText("Código fuente del fork") }
                    },
                    dismissButton = {
                        TvOutlinedButton(onClick = onDismiss) { TvText(stringResource(android.R.string.ok)) }
                    },
                    containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
                    shape = TvMaterialTheme.shapes.extraLarge
                )
            }
        }
        else -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.app_name)) },
                text = {
                    Column {
                        Text("Fork JoseloFarias", style = MaterialTheme.typography.titleMedium)
                        Text(versionName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Basado en kerneldroid/Nightzuku y RikkaApps/Shizuku",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onSourceCode) { Text("Código fuente del fork") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                }
            )
        }
    }
}
