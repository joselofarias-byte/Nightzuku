package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.module.ModuleSettings
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

            ShizukuExpressiveTheme {
                ShizukuLazyScaffold(
                    title = "Lab Features",
                    onNavigateUp = { finish() }
                ) {
                    item {
                        SettingsGroup(title = "Experimental Features") {
                            SwitchSettingsRow(
                                icon = R.drawable.ic_baseline_link_24,
                                title = "Shizuku Connectors",
                                summary = "Allow plugins to activate Shizuku",
                                checked = connectorEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showUnsafeDialog = true
                                    } else {
                                        connectorEnabled = false
                                        ModuleSettings.setConnectorEnabled(false)
                                    }
                                }
                            )
                        }
                    }
                }

                if (showUnsafeDialog) {
                    AlertDialog(
                        onDismissRequest = { showUnsafeDialog = false },
                        title = { Text("Внимание") },
                        text = { Text("Включение этой функции НЕБЕЗОПАСНО. Вы уверены?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showUnsafeDialog = false
                                connectorEnabled = true
                                ModuleSettings.setConnectorEnabled(true)
                            }) {
                                Text("Да")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUnsafeDialog = false }) {
                                Text("Отмена")
                            }
                        }
                    )
                }
            }
        }
    }
}
