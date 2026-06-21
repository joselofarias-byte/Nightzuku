package moe.shizuku.manager.shell

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text as WearText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.ui.compose.ExpressiveCard
import moe.shizuku.manager.ui.compose.HtmlText
import moe.shizuku.manager.ui.compose.MonospaceLog
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.StepRow
import moe.shizuku.manager.utils.CustomTabsHelper

class ShellTutorialActivity : AppActivity() {

    companion object {

        private const val SH_NAME = "rish"
        private const val DEX_NAME = "rish_shizuku.dex"
    }

    private val openDocumentsTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult

            val cr = contentResolver
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val child =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

            cr.query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name == SH_NAME || name == DEX_NAME) {
                        DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }

            fun writeToDocument(name: String) {
                DocumentsContract.createDocument(contentResolver, doc, "application/octet-stream", name)?.runCatching {
                    cr.openOutputStream(this)?.let { assets.open(name).copyTo(it) }
                }
            }

            writeToDocument(SH_NAME)
            writeToDocument(DEX_NAME)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val shName = SH_NAME
            val dexName = DEX_NAME

            val isWatch = moe.shizuku.manager.utils.EnvironmentUtils.isWatch(this@ShellTutorialActivity)
            val isTv = moe.shizuku.manager.utils.EnvironmentUtils.isTV(this@ShellTutorialActivity)
            if (isWatch) {
                moe.shizuku.manager.ui.compose.WearShizukuTheme {
                    moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                        androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                moe.shizuku.manager.ui.compose.WearScreenTitle(
                                    icon = Icons.Rounded.Terminal,
                                    title = stringResource(R.string.home_terminal_title)
                                )
                            }
                            item {
                                WearCard(
                                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    WearText(
                                        text = HtmlText(R.string.rish_description, shName),
                                        style = WearMaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            item {
                                WearCard(
                                    onClick = { openDocumentsTree.launch(null) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.foundation.layout.Column {
                                        WearText(
                                            text = "1. " + HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                            style = WearMaterialTheme.typography.labelMedium
                                        )
                                        WearText(
                                            text = stringResource(R.string.terminal_export_files),
                                            style = WearMaterialTheme.typography.bodySmall,
                                            color = WearMaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            item {
                                WearCard(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.foundation.layout.Column {
                                        WearText(
                                            text = "2. " + stringResource(R.string.terminal_tutorial_2),
                                            style = WearMaterialTheme.typography.labelMedium
                                        )
                                        WearText(
                                            text = stringResource(R.string.terminal_tutorial_2_command),
                                            style = WearMaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            item {
                                WearCard(
                                    onClick = {},
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.foundation.layout.Column {
                                        WearText(
                                            text = "3. " + stringResource(R.string.terminal_tutorial_3),
                                            style = WearMaterialTheme.typography.labelMedium
                                        )
                                        WearText(
                                            text = stringResource(R.string.terminal_tutorial_3_command),
                                            style = WearMaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (isTv) {
                moe.shizuku.manager.ui.compose.TvShizukuTheme {
                    TvShellTutorialScreen(
                        onNavigateUp = { finish() },
                        shName = shName,
                        dexName = dexName,
                        onExportFiles = { openDocumentsTree.launch(null) },
                        onViewHelp = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                    )
                }
            } else {
                ShizukuExpressiveTheme {
                    ShizukuLazyScaffold(
                        title = stringResource(R.string.home_terminal_title),
                        onNavigateUp = { finish() }
                    ) {
                        item {
                            ExpressiveCard(
                                icon = R.drawable.ic_help_outline_24dp,
                                title = stringResource(R.string.home_terminal_title),
                                body = HtmlText(R.string.rish_description, shName),
                                onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                            ) {
                                FilledTonalButton(
                                    onClick = { CustomTabsHelper.launchUrlOrCopy(this@ShellTutorialActivity, Helps.RISH.get()) }
                                ) {
                                    Text(stringResource(R.string.home_adb_button_view_help))
                                }
                            }
                        }
                        item {
                            StepRow(
                                number = 1,
                                title = HtmlText(R.string.terminal_tutorial_1, shName, dexName),
                                body = stringResource(R.string.terminal_tutorial_1_description),
                                action = {
                                    Button(onClick = { openDocumentsTree.launch(null) }) {
                                        Text(stringResource(R.string.terminal_export_files))
                                    }
                                }
                            )
                        }
                        item {
                            StepRow(
                                number = 2,
                                title = stringResource(R.string.terminal_tutorial_2),
                                action = {
                                    androidx.compose.foundation.layout.Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SelectionContainer {
                                            MonospaceLog(text = stringResource(R.string.terminal_tutorial_2_command))
                                        }
                                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        val commandText = stringResource(R.string.terminal_tutorial_2_command)
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(commandText))
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.toast_copied_to_clipboard, context.getString(R.string.copy_commands)),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        ) {
                                            Text(stringResource(R.string.copy_commands))
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            var showAdvancedDialog by remember { mutableStateOf(false) }
                            StepRow(
                                number = 3,
                                title = stringResource(R.string.terminal_tutorial_3),
                                action = {
                                    androidx.compose.foundation.layout.Column(
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SelectionContainer {
                                            MonospaceLog(text = stringResource(R.string.terminal_tutorial_3_command))
                                        }
                                        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        val commandText = stringResource(R.string.terminal_tutorial_3_command)
                                        androidx.compose.material3.TextButton(
                                            onClick = {
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(commandText))
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.toast_copied_to_clipboard, context.getString(R.string.copy_commands)),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        ) {
                                            Text(stringResource(R.string.copy_commands))
                                        }
                                        androidx.compose.material3.TextButton(
                                            onClick = { showAdvancedDialog = true }
                                        ) {
                                            Text(stringResource(R.string.terminal_tutorial_advanced_title))
                                        }
                                    }
                                }
                            )
                            if (showAdvancedDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showAdvancedDialog = false },
                                    title = { Text(stringResource(R.string.terminal_tutorial_advanced_dialog_title)) },
                                    text = { Text(stringResource(R.string.terminal_tutorial_advanced_dialog_message)) },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(
                                            onClick = { showAdvancedDialog = false }
                                        ) {
                                            Text(stringResource(android.R.string.ok))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
