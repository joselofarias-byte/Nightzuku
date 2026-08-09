@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.wear.compose.material3.ExperimentalWearMaterial3Api::class,
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package moe.shizuku.manager.home

import android.content.Intent
import android.net.Uri
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

private const val FORK_REPOSITORY = "https://github.com/joselofarias-byte/Nightzuku"
private const val NIGHTZUKU_UPSTREAM = "https://github.com/kerneldroid/Nightzuku"
private const val SHIZUKU_UPSTREAM = "https://github.com/RikkaApps/Shizuku"

@Composable
internal fun ForkAboutDialog(
    onDismiss: () -> Unit,
    onSourceCode: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val description = "Nightzuku · Fork JoseloFarias\n$versionName\nBasado en kerneldroid/Nightzuku y RikkaApps/Shizuku"

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
                            WearText("Repositorio del fork")
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
                        TvButton(onClick = onSourceCode) { TvText("Repositorio del fork") }
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
                    Column {
                        Text("Fork mantenido por JoseloFarias", style = MaterialTheme.typography.titleMedium)
                        Text(versionName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Basado en proyectos originales con sus licencias y autoría preservadas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onSourceCode,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Repositorio de este fork") }
                        TextButton(
                            onClick = { openRepository(context, NIGHTZUKU_UPSTREAM) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("kerneldroid/Nightzuku") }
                        TextButton(
                            onClick = { openRepository(context, SHIZUKU_UPSTREAM) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("RikkaApps/Shizuku") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                }
            )
        }
    }
}

private fun openRepository(context: android.content.Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
