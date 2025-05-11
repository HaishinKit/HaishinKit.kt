package com.haishinkit.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Suppress("ktlint:standard:function-naming")
@Composable
fun PreferenceScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val keyboardController = LocalSoftwareKeyboardController.current
        var rtmpUrl by remember { mutableStateOf(Preference.shared.rtmpURL) }
        TextField(
            label = { Text("RTMP URL") },
            value = rtmpUrl,
            onValueChange = {
                rtmpUrl = it
                Preference.shared.rtmpURL = it
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = {
                    keyboardController?.hide()
                }),
            modifier =
                Modifier
                    .fillMaxWidth(),
        )
        var streamName by remember { mutableStateOf(Preference.shared.streamName) }
        TextField(
            label = { Text("RTMP StreamName") },
            value = streamName,
            onValueChange = {
                streamName = it
                Preference.shared.streamName = it
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = {
                    keyboardController?.hide()
                }),
            modifier =
                Modifier
                    .fillMaxWidth(),
        )
    }
}
