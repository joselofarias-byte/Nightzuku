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

private const val USDT_ARBITRUM_ADDRESS = "0x5d580ac4f1eabff84379fa8e217df4684ad30934"
private const val OKX_WALLET_LINK = "https://web3.okx.com/ul/N747zv"
private const val BINANCE_PAY_LINK = "https://app.binance.com/uni-qr/Dhzk73AN"
private const val NIGHTZUKU_UPSTREAM = "https://github.com/kerneldroid/Nightzuku"
private const val SHIZUKU_UPSTREAM = "https://github.com/RikkaApps/Shizuku"

@Composable
internal fun CreditsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créditos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Edición y mantenimiento: JoseloFarias")
                Text("Proyecto derivado: kerneldroid/Nightzuku")
                Text("Base original: RikkaApps/Shizuku")
                Text(
                    "La autoría y las licencias originales se mantienen. Puedes agradecer o apoyar a los autores desde sus proyectos oficiales.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { openUrl(context, NIGHTZUKU_UPSTREAM) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Abrir Nightzuku original") }
                TextButton(
                    onClick = { openUrl(context, SHIZUKU_UPSTREAM) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Abrir Shizuku original") }
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
        title = { Text("Apoyar Nightzuku") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("USDT · Arbitrum One", style = MaterialTheme.typography.titleSmall)
                Text(
                    USDT_ARBITRUM_ADDRESS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Enviar únicamente activos compatibles con Arbitrum One.", style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("USDT Arbitrum One", USDT_ARBITRUM_ADDRESS))
                        Toast.makeText(context, "Dirección copiada", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Copiar dirección USDT") }
                TextButton(
                    onClick = { openUrl(context, OKX_WALLET_LINK) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Abrir wallet / QR USDT") }

                Text("Binance Pay", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { openUrl(context, BINANCE_PAY_LINK) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Abrir Binance Pay") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
    }
}
