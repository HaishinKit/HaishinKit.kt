package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep publishing actions visible while camera settings scroll in short windows. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun PublishControls(
    modifier: Modifier = Modifier,
    settings: @Composable ColumnScope.() -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.SpaceBetween) {
        Column(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().padding(bottom = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = settings,
        )
        actions()
    }
}
