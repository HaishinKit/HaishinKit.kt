package com.haishinkit.app

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

class PlaybackTabFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setContent {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                Log.i(TAG, "setContent:${this@PlaybackTabFragment}")
                MaterialTheme {
                    Surface {
                        PlaybackScreen(
                            command = Preference.shared.rtmpURL,
                            streamName = Preference.shared.streamName,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

    companion object {
        fun newInstance(): PlaybackTabFragment = PlaybackTabFragment()

        private const val TAG = "PlaybackTabFragment"
    }
}
