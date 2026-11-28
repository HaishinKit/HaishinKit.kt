package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun NoiseReductionControls(
    checked: Boolean,
    comparisonActive: Boolean,
    enabled: Boolean,
    error: String?,
    onCheckedChange: (Boolean) -> Unit,
    onRestoreSystem: () -> Unit,
) {
    val label = stringResource(R.string.rnnoise_title)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
        }
        Text(stringResource(if (comparisonActive) R.string.rnnoise_comparison_detail else R.string.rnnoise_start_detail), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (comparisonActive) {
            TextButton(onClick = onRestoreSystem, enabled = enabled) { Text(stringResource(R.string.rnnoise_restore_system)) }
        }
    }
}
