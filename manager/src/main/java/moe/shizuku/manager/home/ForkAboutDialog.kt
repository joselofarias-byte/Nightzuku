@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.wear.compose.material3.ExperimentalWearMaterial3Api::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.home

import androidx.compose.foundation.layout.Arrangement
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
    val description = "Versión $versionName\n\nBasado en Nightzuku y Shizuku.\nAutoría y licencias originales preservadas."

    when {
        EnvironmentUtils.isWatch(context) -> {
            moe.shizuku.manager.ui.compose.WearShizukuTheme {
                WearAlertDialog(
                    show = true,
                    onDismissRequest = onDismiss,
                    title = { WearText("Nightzuku · Fork JoseloFarias") },
                    text = { WearText(description) }
                ) {
                    item {
                        WearButton(onClick = onSourceCode, modifier = Modifier.fillMaxWidth()) {
                            WearText("Repositorio de este fork")
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
                    title = { TvText("Nightzuku · Fork JoseloFarias") },
                    text = { TvText(description) },
                    confirmButton = {
                        TvButton(onClick = onSourceCode) { TvText("Repositorio de este fork") }
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
                title = { Text("Nightzuku · Fork JoseloFarias") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Versión $versionName", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Basado en Nightzuku y Shizuku. Autoría y licencias originales preservadas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onSourceCode,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Repositorio de este fork")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                }
            )
        }
    }
}
