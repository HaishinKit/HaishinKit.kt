package com.haishinkit.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.screen.Screen
import java.io.ByteArrayOutputStream

interface CameraController {
    val videoEffectItems: List<VideoEffectItem>

    fun onScreenShot(screen: Screen)
}

class CameraTabFragment :
    Fragment(),
    CameraController {
    override val videoEffectItems: List<VideoEffectItem> by lazy {
        val items = mutableListOf<VideoEffectItem>()
        items.add(VideoEffectItem("Normal", null))
        items.add(VideoEffectItem("Monochrome", MonochromeVideoEffect()))
        items.add(VideoEffectItem("Mosaic", MosaicVideoEffect()))
        items.add(VideoEffectItem("Sephia", SepiaVideoEffect()))
        items
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Log.i(TAG, "setContent:${this@CameraTabFragment}")
                MaterialTheme {
                    Surface {
                        CameraScreen(
                            command = Preference.shared.rtmpURL,
                            streamName = Preference.shared.streamName,
                            controller = this@CameraTabFragment,
                        )
                    }
                }
            }
        }

    override fun onScreenShot(screen: Screen) {
        screen.readPixels {
            val bitmap = it ?: return@readPixels
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
            val path =
                MediaStore.Images.Media.insertImage(
                    requireActivity().contentResolver,
                    bitmap,
                    "Title",
                    null,
                )
            val imageUri = Uri.parse(path)
            val share = Intent(Intent.ACTION_SEND)
            share.setType("image/jpeg")
            share.putExtra(Intent.EXTRA_STREAM, imageUri)
            startActivity(Intent.createChooser(share, "Select"))
        }
    }

    companion object {
        private const val TAG = "CameraTabFragment"

        fun newInstance(): CameraTabFragment = CameraTabFragment()
    }
}
