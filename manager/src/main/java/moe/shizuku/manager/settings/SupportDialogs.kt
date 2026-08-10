package moe.shizuku.manager.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val EVM_ADDRESS = "0x5d580ac4f1eabff84379fa8e217df4684ad30934"
private const val BINANCE_PAY_ID = "282919237"
private const val FORK_REPOSITORY = "https://github.com/joselofarias-byte/Nightzuku"

@Composable
internal fun CreditsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nightzuku · Fork JoseloFarias") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fork mantenido por JoseloFarias")
                TextButton(
                    onClick = { openUrl(context, FORK_REPOSITORY) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Repositorio de este fork") }

                Text(
                    "Proyectos originales",
                    style = MaterialTheme.typography.titleSmall
                )
                Text("kerneldroid/Nightzuku")
                Text("RikkaApps/Shizuku")
                Text(
                    "Se mantienen sus menciones y atribuciones originales.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Aceptar") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
internal fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apoyar este fork") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dirección EVM", style = MaterialTheme.typography.titleSmall)
                Text(
                    EVM_ADDRESS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Para cualquier red EVM y token compatible. Verifica siempre la red y el token antes de enviar.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = {
                        copyText(context, "Dirección EVM", EVM_ADDRESS)
                        Toast.makeText(context, "Dirección EVM copiada", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copiar dirección EVM") }

                Text("Binance Pay", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ID: $BINANCE_PAY_ID",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = {
                        copyText(context, "Binance Pay ID", BINANCE_PAY_ID)
                        Toast.makeText(context, "Binance Pay ID copiado", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copiar Binance Pay ID") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "No se pudo abrir el repositorio", Toast.LENGTH_SHORT).show()
    }
}
