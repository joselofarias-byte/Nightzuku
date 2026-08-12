@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package moe.shizuku.manager.settings

import android.content.ComponentName
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Text as TvText
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.OutlinedButton as TvOutlinedButton
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.ClickableSurfaceDefaults as TvClickableSurfaceDefaults
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LANGUAGE
import moe.shizuku.manager.ShizukuSettings.NIGHT_MODE
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.app.ThemeHelper.KEY_USE_SYSTEM_COLOR
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.ktx.setComponentEnabled
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.shizuku.NightDogRecovery
import rikka.shizuku.Shizuku
import moe.shizuku.manager.ui.compose.GroupDivider
import moe.shizuku.manager.ui.compose.NightzukuSwitchSettingsRow as SwitchSettingsRow
import moe.shizuku.manager.ui.compose.SettingsGroup
import moe.shizuku.manager.ui.compose.SettingsRow
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme
import moe.shizuku.manager.ui.compose.ShizukuLazyScaffold
import moe.shizuku.manager.ui.compose.ShizukuIcon
import moe.shizuku.manager.ui.compose.htmlToPlainText
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.core.util.ResourceUtils
import rikka.material.app.LocaleDelegate
import rikka.shizuku.manager.ShizukuLocales
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.CheckboxButton as WearCheckboxButton
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.RadioButton as WearRadioButton
import androidx.wear.compose.material3.Text as WearText
import java.util.Locale

class SettingsActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val componentName = ComponentName(packageName, BootCompleteReceiver::class.java.name)

        setContent {
            val prefs = ShizukuSettings.getPreferences()
            var startOnBoot by remember {
                mutableStateOf(packageManager.isComponentEnabled(componentName))
            }
            var languageTag by remember {
                mutableStateOf(prefs.getString(LANGUAGE, "SYSTEM") ?: "SYSTEM")
            }
            var nightMode by remember {
                mutableIntStateOf(ShizukuSettings.getNightMode())
            }
            var blackNightTheme by remember {
                mutableStateOf(ThemeHelper.isBlackNightTheme(this))
            }
            var useSystemColor by remember {
                mutableStateOf(ThemeHelper.isUsingSystemColor())
            }
            var showLanguageDialog by remember { mutableStateOf(false) }
            var showNightDialog by remember { mutableStateOf(false) }
            var showModuleModeDialog by remember { mutableStateOf(false) }
            var showCustomPermissionsDialog by remember { mutableStateOf(false) }
            var showStopDialog by remember { mutableStateOf(false) }
            var showCreditsDialog by remember { mutableStateOf(false) }
            var showSupportDialog by remember { mutableStateOf(false) }

            var moduleAccessMode by remember {
                mutableStateOf(ModuleSettings.getAccessMode())
            }
            var customPermissions by remember {
                mutableStateOf(ModuleSettings.getCustomPermissions())
            }
            var recommandWebUi by remember {
                mutableStateOf(ModuleSettings.recommandForWebUi())
            }
            var recommandAction by remember {
                mutableStateOf(ModuleSettings.recommandForAction())
            }
            var recreateTick by remember { mutableIntStateOf(0) }

            val localeOptions = remember(languageTag) { buildLocaleOptions(languageTag) }
            val languageSummary = localeOptions.firstOrNull { it.tag == languageTag }?.title
                ?: stringResource(rikka.core.R.string.follow_system)
            val nightValues = resources.getIntArray(R.array.night_mode_value).toList()
            val nightLabels = stringArrayResource(R.array.night_mode).toList()
            val nightSummary = nightLabels.getOrElse(nightValues.indexOf(nightMode)) {
                stringResource(rikka.core.R.string.follow_system)
            }
            val contributors = htmlToPlainText(getString(R.string.translation_contributors))
            val isRooted = remember { EnvironmentUtils.isRooted() }

            LaunchedEffect(recreateTick) {
                if (recreateTick > 0) {
                    delay(260)
                    recreate()
                }
            }

            val isWatch = EnvironmentUtils.isWatch(this@SettingsActivity)
            val isTv = EnvironmentUtils.isTV(this@SettingsActivity)

            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                if (isWatch) {
                    moe.shizuku.manager.ui.compose.WearShizukuTheme {
                        WearSettingsScreen(
                            startOnBoot = startOnBoot,
                            onStartOnBootChange = { enabled ->
                                packageManager.setComponentEnabled(componentName, enabled)
                                startOnBoot = packageManager.isComponentEnabled(componentName)
                            },
                            nightModeSummary = nightSummary,
                            onNightModeClick = { showNightDialog = true },
                            blackNightTheme = blackNightTheme,
                            onBlackNightThemeChange = { enabled ->
                                prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                blackNightTheme = enabled
                                if (ResourceUtils.isNightMode(resources.configuration)) recreateTick++
                            },
                            useSystemColor = useSystemColor,
                            onUseSystemColorChange = { enabled ->
                                prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                useSystemColor = enabled
                                recreateTick++
                            },
                            onLabFeaturesClick = {
                                startActivity(android.content.Intent(this@SettingsActivity, LabFeaturesActivity::class.java))
                            },
                            moduleAccessMode = moduleAccessMode,
                            onModuleAccessModeClick = { showModuleModeDialog = true },
                            onCustomPermissionsClick = { showCustomPermissionsDialog = true },
                            showNightDialog = false,
                            nightLabels = nightLabels,
                            nightValues = nightValues,
                            currentNightMode = nightMode,
                            onNightModeSelect = { },
                            onNightDialogDismiss = { }
                        )
                    }
                } else if (isTv) {
                    moe.shizuku.manager.ui.compose.TvShizukuTheme {
                        TvSettingsScreen(
                            onNavigateUp = { finish() },
                            startOnBoot = startOnBoot,
                            onStartOnBootChange = { enabled ->
                                packageManager.setComponentEnabled(componentName, enabled)
                                startOnBoot = packageManager.isComponentEnabled(componentName)
                            },
                            languageSummary = languageSummary,
                            onLanguageClick = { showLanguageDialog = true },
                            nightSummary = nightSummary,
                            onNightModeClick = { showNightDialog = true },
                            blackNightTheme = blackNightTheme,
                            onBlackNightThemeChange = { enabled ->
                                prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                blackNightTheme = enabled
                                if (ResourceUtils.isNightMode(resources.configuration)) recreateTick++
                            },
                            useSystemColor = useSystemColor,
                            onUseSystemColorChange = { enabled ->
                                prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                useSystemColor = enabled
                                recreateTick++
                            },
                            moduleAccessMode = moduleAccessMode,
                            onModuleAccessModeClick = { showModuleModeDialog = true },
                            recommandWebUi = recommandWebUi,
                            onRecommandWebUiChange = { enabled ->
                                ModuleSettings.setRecommandForWebUi(enabled)
                                recommandWebUi = enabled
                            },
                            recommandAction = recommandAction,
                            onRecommandActionChange = { enabled ->
                                ModuleSettings.setRecommandForAction(enabled)
                                recommandAction = enabled
                            }
                        )
                    }
                } else {
                    ShizukuExpressiveTheme {
                        ShizukuLazyScaffold(
                            title = stringResource(R.string.settings_title),
                            onNavigateUp = { finish() }
                        ) {
                            if (isRooted) {
                                item {
                                    SettingsGroup(title = stringResource(R.string.settings_startup)) {
                                        SwitchSettingsRow(
                                            icon = R.drawable.ic_server_restart,
                                            title = stringResource(R.string.settings_start_on_boot),
                                            summary = stringResource(R.string.settings_start_on_boot_summary),
                                            checked = startOnBoot,
                                            onCheckedChange = { enabled ->
                                                packageManager.setComponentEnabled(componentName, enabled)
                                                startOnBoot = packageManager.isComponentEnabled(componentName)
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                SettingsGroup(title = stringResource(R.string.settings_language)) {
                                    SettingsRow(
                                        icon = R.drawable.ic_outline_translate_24,
                                        title = stringResource(R.string.settings_language),
                                        summary = languageSummary,
                                        onClick = { showLanguageDialog = true }
                                    )
                                    GroupDivider()
                                    if (contributors.isNotBlank()) {
                                        SettingsRow(
                                            icon = R.drawable.ic_outline_info_24,
                                            title = stringResource(R.string.settings_translation_contributors),
                                            summary = contributors,
                                            onClick = { }
                                        )
                                        GroupDivider()
                                    }
                                    SettingsRow(
                                        icon = R.drawable.ic_baseline_link_24,
                                        title = stringResource(R.string.settings_translation),
                                        summary = stringResource(
                                            R.string.settings_translation_summary,
                                            stringResource(R.string.app_name)
                                        ),
                                        onClick = {
                                            CustomTabsHelper.launchUrlOrCopy(this@SettingsActivity, getString(R.string.translation_url))
                                        }
                                    )
                                }
                            }

                            item {
                                SettingsGroup(title = stringResource(R.string.settings_user_interface)) {
                                    SettingsRow(
                                        icon = R.drawable.ic_outline_dark_mode_24,
                                        title = stringResource(rikka.core.R.string.dark_theme),
                                        summary = nightSummary,
                                        onClick = { showNightDialog = true }
                                    )
                                    if (nightMode != AppCompatDelegate.MODE_NIGHT_NO) {
                                        GroupDivider()
                                        SwitchSettingsRow(
                                            icon = R.drawable.ic_outline_dark_mode_24,
                                            title = stringResource(R.string.settings_black_night_theme),
                                            summary = stringResource(R.string.settings_black_night_theme_summary),
                                            checked = blackNightTheme,
                                            onCheckedChange = { enabled ->
                                                prefs.edit().putBoolean(KEY_BLACK_NIGHT_THEME, enabled).apply()
                                                blackNightTheme = enabled
                                                if (ResourceUtils.isNightMode(resources.configuration)) recreateTick++
                                            }
                                        )
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        GroupDivider()
                                        SwitchSettingsRow(
                                            icon = R.drawable.ic_settings_outline_24dp,
                                            title = stringResource(R.string.settings_use_system_color),
                                            checked = useSystemColor,
                                            onCheckedChange = { enabled ->
                                                prefs.edit().putBoolean(KEY_USE_SYSTEM_COLOR, enabled).apply()
                                                useSystemColor = enabled
                                                recreateTick++
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                SettingsGroup(title = stringResource(R.string.lab_features_title)) {
                                    SettingsRow(
                                        icon = R.drawable.ic_settings_outline_24dp,
                                        title = stringResource(R.string.lab_features_title),
                                        summary = stringResource(R.string.lab_features_summary),
                                        onClick = { startActivity(android.content.Intent(this@SettingsActivity, LabFeaturesActivity::class.java)) }
                                    )
                                }
                            }

                            item {
                                SettingsGroup(title = "Créditos y apoyo") {
                                    SettingsRow(
                                        icon = R.drawable.ic_outline_info_24,
                                        title = "Créditos y origen",
                                        summary = "JoseloFarias · fork Nightzuku · basado en Shizuku",
                                        onClick = { showCreditsDialog = true }
                                    )
                                    GroupDivider()
                                    SettingsRow(
                                        icon = R.drawable.ic_baseline_link_24,
                                        title = "Apoyar este fork",
                                        summary = "USDT · Arbitrum One · Binance Pay",
                                        onClick = { showSupportDialog = true }
                                    )
                                }
                            }

                            item {
                                SettingsGroup(title = stringResource(R.string.diagnostic_service)) {
                                    SettingsRow(
                                        icon = R.drawable.ic_close_24,
                                        title = stringResource(R.string.action_stop),
                                        summary = stringResource(
                                            R.string.home_status_service_is_running,
                                            stringResource(R.string.app_name)
                                        ),
                                        onClick = { showStopDialog = true }
                                    )
                                }
                            }

                            item {
                                SettingsGroup(title = stringResource(R.string.modules_settings_title)) {
                                    SettingsRow(
                                        icon = R.drawable.ic_settings_outline_24dp,
                                        title = stringResource(R.string.modules_access_mode),
                                        summary = stringResource(moduleAccessMode.labelRes),
                                        onClick = { showModuleModeDialog = true }
                                    )
                                    if (moduleAccessMode == ModuleSettings.AccessMode.CUSTOM) {
                                        GroupDivider()
                                        SettingsRow(
                                            icon = R.drawable.ic_add_24,
                                            title = stringResource(R.string.modules_custom_permissions),
                                            summary = stringResource(R.string.modules_custom_permissions_summary),
                                            onClick = { showCustomPermissionsDialog = true }
                                        )
                                    }
                                    GroupDivider()
                                    SwitchSettingsRow(
                                        icon = R.drawable.ic_warning_24,
                                        title = stringResource(R.string.modules_recommand_webui),
                                        summary = stringResource(R.string.modules_recommand_webui_summary),
                                        checked = recommandWebUi,
                                        onCheckedChange = { enabled ->
                                            ModuleSettings.setRecommandForWebUi(enabled)
                                            recommandWebUi = enabled
                                        }
                                    )
                                    GroupDivider()
                                    SwitchSettingsRow(
                                        icon = R.drawable.ic_warning_24,
                                        title = stringResource(R.string.modules_recommand_action),
                                        summary = stringResource(R.string.modules_recommand_action_summary),
                                        checked = recommandAction,
                                        onCheckedChange = { enabled ->
                                            ModuleSettings.setRecommandForAction(enabled)
                                            recommandAction = enabled
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (showCreditsDialog && !isWatch && !isTv) {
                    ShizukuExpressiveTheme { CreditsDialog(onDismiss = { showCreditsDialog = false }) }
                }
                if (showSupportDialog && !isWatch && !isTv) {
                    ShizukuExpressiveTheme { SupportDialog(onDismiss = { showSupportDialog = false }) }
                }
                if (showStopDialog) {
                    ShizukuExpressiveTheme {
                        AlertDialog(
                            onDismissRequest = { showStopDialog = false },
                            title = { Text(stringResource(R.string.action_stop)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    NightDogRecovery.prepareForManualStop()
                                    runCatching { Shizuku.exit() }
                                    showStopDialog = false
                                    finish()
                                }) { Text(stringResource(R.string.action_stop)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showStopDialog = false }) { Text(stringResource(android.R.string.cancel)) }
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.extraLarge
                        )
                    }
                }

                if (showLanguageDialog) {
                    val choices = remember {
                        localeOptions.map { ChoiceOption(it.title, it.summary, R.drawable.ic_outline_translate_24) }
                    }
                    val selectedIndex = localeOptions.indexOfFirst { it.tag == languageTag }
                    val onSelect: (Int) -> Unit = { index ->
                        val tag = localeOptions[index].tag
                        prefs.edit().putString(LANGUAGE, tag).apply()
                        languageTag = tag
                        LocaleDelegate.defaultLocale = if (tag == "SYSTEM") LocaleDelegate.systemLocale else Locale.forLanguageTag(tag)
                        showLanguageDialog = false
                        recreate()
                    }
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(stringResource(R.string.settings_language), choices, selectedIndex, { showLanguageDialog = false }, onSelect)
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(stringResource(R.string.settings_language), choices, selectedIndex, { showLanguageDialog = false }, onSelect)
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(stringResource(R.string.settings_language), choices, selectedIndex, { showLanguageDialog = false }, onSelect)
                        }
                    }
                }

                if (showNightDialog) {
                    val choices = remember {
                        nightValues.mapIndexed { index, value ->
                            ChoiceOption(
                                title = nightLabels[index],
                                icon = when (value) {
                                    AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_outline_light_mode_24
                                    AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_outline_dark_mode_24
                                    else -> R.drawable.ic_settings_outline_24dp
                                }
                            )
                        }
                    }
                    val selectedIndex = nightValues.indexOf(nightMode)
                    val onSelect: (Int) -> Unit = { index ->
                        val value = nightValues[index]
                        prefs.edit().putInt(NIGHT_MODE, value).apply()
                        nightMode = value
                        AppCompatDelegate.setDefaultNightMode(value)
                        showNightDialog = false
                        recreate()
                    }
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(stringResource(rikka.core.R.string.dark_theme), choices, selectedIndex, { showNightDialog = false }, onSelect)
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(stringResource(rikka.core.R.string.dark_theme), choices, selectedIndex, { showNightDialog = false }, onSelect)
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(stringResource(rikka.core.R.string.dark_theme), choices, selectedIndex, { showNightDialog = false }, onSelect)
                        }
                    }
                }

                if (showModuleModeDialog) {
                    val moduleModes = remember { listOf(ModuleSettings.AccessMode.SAFE, ModuleSettings.AccessMode.CUSTOM, ModuleSettings.AccessMode.FULL) }
                    val choices = remember {
                        moduleModes.map { ChoiceOption(getString(it.labelRes), getString(it.summaryRes), R.drawable.ic_adb_24dp) }
                    }
                    val selectedIndex = moduleModes.indexOf(moduleAccessMode)
                    val onSelect: (Int) -> Unit = { index ->
                        val mode = moduleModes[index]
                        ModuleSettings.setAccessMode(mode)
                        moduleAccessMode = mode
                        showModuleModeDialog = false
                    }
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearChoiceDialog(stringResource(R.string.modules_access_mode), choices, selectedIndex, { showModuleModeDialog = false }, onSelect)
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvChoiceDialog(stringResource(R.string.modules_access_mode), choices, selectedIndex, { showModuleModeDialog = false }, onSelect)
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            ChoiceDialog(stringResource(R.string.modules_access_mode), choices, selectedIndex, { showModuleModeDialog = false }, onSelect)
                        }
                    }
                }

                if (showCustomPermissionsDialog) {
                    val onSave: (ModuleSettings.CustomPermissions) -> Unit = { value ->
                        ModuleSettings.setCustomPermissions(value)
                        customPermissions = value
                        showCustomPermissionsDialog = false
                    }
                    if (isWatch) {
                        moe.shizuku.manager.ui.compose.WearShizukuTheme {
                            WearCustomPermissionsDialog(customPermissions, { showCustomPermissionsDialog = false }, onSave)
                        }
                    } else if (isTv) {
                        moe.shizuku.manager.ui.compose.TvShizukuTheme {
                            TvCustomPermissionsDialog(customPermissions, { showCustomPermissionsDialog = false }, onSave)
                        }
                    } else {
                        ShizukuExpressiveTheme {
                            CustomPermissionsDialog(customPermissions, { showCustomPermissionsDialog = false }, onSave)
                        }
                    }
                }
            }
        }
    }

    private fun buildLocaleOptions(currentTag: String): List<LocaleOption> {
        val localeTags = ShizukuLocales.LOCALES
        val displayLocaleTags = ShizukuLocales.DISPLAY_LOCALES
        val currentLocale = ShizukuSettings.getLocale()
        return localeTags.mapIndexed { index, tag ->
            if (index == 0) {
                LocaleOption(tag.toString(), getString(rikka.core.R.string.follow_system), null)
            } else {
                val locale = Locale.forLanguageTag(displayLocaleTags[index].toString())
                var localeName = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(locale) else locale.getDisplayName(locale)
                if (displayLocaleTags[index].toString() == "es") localeName = "Español"
                var localizedLocaleName = if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(currentLocale) else locale.getDisplayName(currentLocale)
                if (displayLocaleTags[index].toString() == "es" && currentLocale.language == "es") localizedLocaleName = "Español"
                LocaleOption(
                    tag = tag.toString(),
                    title = if (tag.toString() == currentTag) localizedLocaleName else localeName,
                    summary = if (tag.toString() == currentTag || localeName == localizedLocaleName) null else localizedLocaleName
                )
            }
        }
    }

    private data class LocaleOption(val tag: String, val title: String, val summary: String?)
}

private data class ChoiceOption(
    val title: String,
    val summary: String? = null,
    @param:androidx.annotation.DrawableRes val icon: Int? = null
)

@Composable
private fun WearChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    moe.shizuku.manager.ui.compose.WearShizukuTheme {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(WearMaterialTheme.colorScheme.background)) {
            moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        WearText(
                            text = title,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            color = WearMaterialTheme.colorScheme.primary,
                            style = WearMaterialTheme.typography.titleMedium
                        )
                    }
                    for (index in choices.indices) {
                        val choiceTitle = choices[index].title
                        item {
                            WearRadioButton(
                                selected = index == selectedIndex,
                                onSelect = { onSelect(index) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { WearText(choiceTitle) }
                            )
                        }
                    }
                    item {
                        WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            WearText(stringResource(android.R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WearCustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    val title = stringResource(R.string.modules_custom_permissions)
    val actionText = stringResource(R.string.modules_permission_action)
    val serviceText = stringResource(R.string.modules_permission_service)
    val webBridgeText = stringResource(R.string.modules_permission_web_bridge)
    val webNetworkText = stringResource(R.string.modules_permission_web_network)
    val webDownloadText = stringResource(R.string.modules_permission_web_download)
    val okText = stringResource(android.R.string.ok)
    val cancelText = stringResource(android.R.string.cancel)

    moe.shizuku.manager.ui.compose.WearShizukuTheme {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().background(WearMaterialTheme.colorScheme.background)) {
            moe.shizuku.manager.ui.compose.WearScreenScaffold { state ->
                androidx.wear.compose.foundation.lazy.TransformingLazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        WearText(
                            text = title,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            color = WearMaterialTheme.colorScheme.primary,
                            style = WearMaterialTheme.typography.titleMedium
                        )
                    }
                    item { WearCheckboxButton(checked = draft.action, onCheckedChange = { draft = draft.copy(action = it) }, modifier = Modifier.fillMaxWidth(), label = { WearText(actionText) }) }
                    item { WearCheckboxButton(checked = draft.service, onCheckedChange = { draft = draft.copy(service = it) }, modifier = Modifier.fillMaxWidth(), label = { WearText(serviceText) }) }
                    item {
                        WearCheckboxButton(
                            checked = draft.webBridge,
                            onCheckedChange = { enabled -> draft = if (enabled) draft.copy(webBridge = true, webNetwork = false) else draft.copy(webBridge = false) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { WearText(webBridgeText) }
                        )
                    }
                    item {
                        WearCheckboxButton(
                            checked = draft.webNetwork,
                            onCheckedChange = { enabled -> draft = if (enabled) draft.copy(webNetwork = true, webBridge = false) else draft.copy(webNetwork = false) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { WearText(webNetworkText) }
                        )
                    }
                    item { WearCheckboxButton(checked = draft.webDownload, onCheckedChange = { draft = draft.copy(webDownload = it) }, modifier = Modifier.fillMaxWidth(), label = { WearText(webDownloadText) }) }
                    item { WearButton(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) { WearText(okText) } }
                    item { WearFilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { WearText(cancelText) } }
                }
            }
        }
    }
}

@Composable
private fun CustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modules_custom_permissions)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                SwitchSettingsRow(R.drawable.ic_outline_play_arrow_24, stringResource(R.string.modules_permission_action), stringResource(R.string.modules_permission_action_summary), draft.action) { draft = draft.copy(action = it) }
                GroupDivider()
                SwitchSettingsRow(R.drawable.ic_terminal_24, stringResource(R.string.modules_permission_service), stringResource(R.string.modules_permission_service_summary), draft.service) { draft = draft.copy(service = it) }
                GroupDivider()
                SwitchSettingsRow(R.drawable.ic_code_24dp, stringResource(R.string.modules_permission_web_bridge), stringResource(R.string.modules_permission_web_bridge_summary), draft.webBridge) { enabled ->
                    draft = if (enabled) draft.copy(webBridge = true, webNetwork = false) else draft.copy(webBridge = false)
                }
                GroupDivider()
                SwitchSettingsRow(R.drawable.ic_baseline_link_24, stringResource(R.string.modules_permission_web_network), stringResource(R.string.modules_permission_web_network_summary), draft.webNetwork) { enabled ->
                    draft = if (enabled) draft.copy(webNetwork = true, webBridge = false) else draft.copy(webNetwork = false)
                }
                GroupDivider()
                SwitchSettingsRow(R.drawable.ic_outline_arrow_upward_24, stringResource(R.string.modules_permission_web_download), stringResource(R.string.modules_permission_web_download_summary), draft.webDownload) { draft = draft.copy(webDownload = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((index, choice) in choices.withIndex()) {
                    SettingsRow(
                        icon = choice.icon,
                        title = choice.title,
                        summary = choice.summary,
                        onClick = { onSelect(index) },
                        trailing = {
                            RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun TvChoiceDialog(
    title: String,
    choices: List<ChoiceOption>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TvText(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((index, choice) in choices.withIndex()) {
                    TvChoiceRow(choice.icon, choice.title, choice.summary, index == selectedIndex) { onSelect(index) }
                }
            }
        },
        confirmButton = { TvOutlinedButton(onClick = onDismiss) { TvText(stringResource(android.R.string.cancel)) } },
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun TvChoiceRow(icon: Int?, title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    val alpha = if (isSystemInDarkTheme()) 0.3f else 0.12f
    TvSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (selected) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha) else Color.Transparent,
            focusedContainerColor = if (selected) TvMaterialTheme.colorScheme.primaryContainer else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (icon != null) ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                TvText(text = title, style = TvMaterialTheme.typography.titleMedium)
                if (summary != null) TvText(text = summary, style = TvMaterialTheme.typography.bodySmall, color = TvMaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) ShizukuIcon(imageVector = Icons.Rounded.Check, modifier = Modifier.size(24.dp), tint = TvMaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TvCustomPermissionsDialog(
    value: ModuleSettings.CustomPermissions,
    onDismiss: () -> Unit,
    onSave: (ModuleSettings.CustomPermissions) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TvText(stringResource(R.string.modules_custom_permissions)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                TvSwitchSettingsRow(R.drawable.ic_outline_play_arrow_24, stringResource(R.string.modules_permission_action), stringResource(R.string.modules_permission_action_summary), draft.action) { draft = draft.copy(action = it) }
                TvSwitchSettingsRow(R.drawable.ic_terminal_24, stringResource(R.string.modules_permission_service), stringResource(R.string.modules_permission_service_summary), draft.service) { draft = draft.copy(service = it) }
                TvSwitchSettingsRow(R.drawable.ic_code_24dp, stringResource(R.string.modules_permission_web_bridge), stringResource(R.string.modules_permission_web_bridge_summary), draft.webBridge) { enabled ->
                    draft = if (enabled) draft.copy(webBridge = true, webNetwork = false) else draft.copy(webBridge = false)
                }
                TvSwitchSettingsRow(R.drawable.ic_baseline_link_24, stringResource(R.string.modules_permission_web_network), stringResource(R.string.modules_permission_web_network_summary), draft.webNetwork) { enabled ->
                    draft = if (enabled) draft.copy(webNetwork = true, webBridge = false) else draft.copy(webNetwork = false)
                }
                TvSwitchSettingsRow(R.drawable.ic_outline_arrow_upward_24, stringResource(R.string.modules_permission_web_download), stringResource(R.string.modules_permission_web_download_summary), draft.webDownload) { draft = draft.copy(webDownload = it) }
            }
        },
        confirmButton = { TvButton(onClick = { onSave(draft) }) { TvText(stringResource(android.R.string.ok)) } },
        dismissButton = { TvOutlinedButton(onClick = onDismiss) { TvText(stringResource(android.R.string.cancel)) } },
        containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
        shape = TvMaterialTheme.shapes.extraLarge
    )
}

@Composable
private fun TvSwitchSettingsRow(
    icon: Int,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    TvSurface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = TvClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.medium),
        colors = TvClickableSurfaceDefaults.colors(
            containerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = if (checked) TvMaterialTheme.colorScheme.primaryContainer else TvMaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShizukuIcon(icon = icon, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                TvText(text = title, style = TvMaterialTheme.typography.titleMedium)
                TvText(text = summary, style = TvMaterialTheme.typography.bodySmall, color = TvMaterialTheme.colorScheme.onSurfaceVariant)
            }
            moe.shizuku.manager.ui.compose.ExpressiveSwitch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
