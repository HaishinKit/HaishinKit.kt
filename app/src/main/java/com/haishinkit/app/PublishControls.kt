package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep primary publishing actions visible while the tool panel scrolls. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PublishControls(
    modifier: Modifier = Modifier,
    settings: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Surface(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(bottom = 12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = settings,
            )
        }
        actions()
    }
}
