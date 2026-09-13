package moe.shizuku.manager.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Phone/tablet settings switch with an explicit Nightzuku palette.
 * This avoids legacy/system switch tints leaking pink/burgundy into our own UI.
 */
@Composable
fun NightzukuSwitchSettingsRow(
    @DrawableRes icon: Int,
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    val checkedThumb = if (dark) Color(0xFF381E72) else Color.White
    val uncheckedTrack = if (dark) Color(0xFF343138) else Color(0xFFE7E2E9)
    val uncheckedThumb = if (dark) Color(0xFFCAC4D0) else Color(0xFF79747E)
    val outline = if (dark) Color(0xFF938F99) else Color(0xFF79747E)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShizukuIcon(
            icon = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(20.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = checkedThumb,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = uncheckedThumb,
                uncheckedTrackColor = uncheckedTrack,
                uncheckedBorderColor = outline,
                disabledCheckedThumbColor = checkedThumb.copy(alpha = 0.55f),
                disabledCheckedTrackColor = accent.copy(alpha = 0.35f),
                disabledUncheckedThumbColor = uncheckedThumb.copy(alpha = 0.55f),
                disabledUncheckedTrackColor = uncheckedTrack.copy(alpha = 0.55f),
                disabledUncheckedBorderColor = outline.copy(alpha = 0.45f)
            )
        )
    }
}
