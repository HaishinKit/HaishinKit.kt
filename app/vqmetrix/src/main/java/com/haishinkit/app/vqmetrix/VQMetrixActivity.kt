package com.haishinkit.app.vqmetrix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.haishinkit.vqmetrix.BatchArchive
import com.haishinkit.vqmetrix.BatchConfig
import com.haishinkit.vqmetrix.BatchPipeline
import com.haishinkit.vqmetrix.BatchProgress
import com.haishinkit.vqmetrix.BatchUploader
import com.haishinkit.vqmetrix.ReceiverDiscovery
import com.haishinkit.vqmetrix.ReceiverEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

class VQMetrixActivity : AppCompatActivity() {
    private lateinit var source: Spinner
    private lateinit var resolution: Spinner
    private lateinit var fps: Spinner
    private lateinit var codec: Spinner
    private lateinit var duration: EditText
    private lateinit var bitrates: EditText
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var saved: Spinner
    private lateinit var generate: Button
    private lateinit var discover: Button
    private lateinit var receivers: Spinner
    private var endpoints = emptyList<ReceiverEndpoint>()
    private val discovery by lazy { ReceiverDiscovery(this) }
    private var discoveryPermissionPending = false
    private lateinit var upload: Button
    private lateinit var share: Button
    private lateinit var language: Button
    private lateinit var cancel: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: ImageView
    private val uploader = BatchUploader()
    private var job: Job? = null
    private var permissionPending: BatchConfig? = null
    private var archives = emptyList<BatchArchive>()
    private var selected: BatchArchive? = null
    private var busy = false
    private var networkPermissionPending = false

    override fun attachBaseContext(base: Context) {
        val tag = base.getSharedPreferences("vqmetrix-ui", MODE_PRIVATE).getString("language", "").orEmpty()
        val localized =
            if (tag.isEmpty()) {
                base
            } else {
                base.createConfigurationContext(
                    Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
                )
            }
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(24))
            }
        scroll.addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        fun label(
            id: Int,
            heading: Boolean = false,
        ) = TextView(this).apply {
            text = getString(id)
            textSize = if (heading) 20f else 14f
            if (heading) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
            content.addView(this)
        }

        fun spinner(items: List<String>) =
            Spinner(this).apply {
                adapter = ArrayAdapter(this@VQMetrixActivity, android.R.layout.simple_spinner_dropdown_item, items)
                content.addView(this, LinearLayout.LayoutParams(-1, dp(50)))
            }

        fun field(
            value: String,
            type: Int = InputType.TYPE_CLASS_TEXT,
        ) = EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            content.addView(this)
        }

        fun button(
            id: Int,
            action: () -> Unit,
        ) = Button(this).apply {
            text = getString(id)
            setOnClickListener { action() }
            content.addView(this)
        }
        label(R.string.vq_app_name, true).textSize = 30f
        label(R.string.vq_subtitle)
        language = button(R.string.vq_language, ::chooseLanguage)
        label(R.string.vq_source)
        source = spinner(listOf(getString(R.string.vq_pattern)))
        label(R.string.vq_resolution)
        resolution = spinner(listOf(getString(R.string.vq_sd), getString(R.string.vq_hd)))
        label(R.string.vq_fps)
        fps = spinner(listOf("15 fps", "30 fps")).apply { setSelection(1) }
        label(R.string.vq_duration)
        duration = field("3", InputType.TYPE_CLASS_NUMBER)
        label(R.string.vq_codec)
        codec = spinner(listOf("H.264", "HEVC"))
        label(R.string.vq_bitrates)
        bitrates = field("0.5, 1, 2")
        generate = button(R.string.vq_generate, ::createBatch)
        cancel =
            button(R.string.vq_cancel) {
                uploader.cancel()
                job?.cancel()
            }
        label(R.string.vq_preview)
        preview =
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.vq_preview)
                content.addView(this, LinearLayout.LayoutParams(-1, dp(160)))
            }
        progress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                content.addView(this)
            }
        status = label(R.string.vq_idle)
        label(R.string.vq_runs, true)
        saved = spinner(listOf(getString(R.string.vq_no_runs)))
        saved.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    selected = archives.getOrNull(position)
                    updateEnabled()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        share = button(R.string.vq_share, ::shareBatch)
        label(R.string.vq_receiver, true)
        server = field(getPreferences(MODE_PRIVATE).getString("receiver", "").orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        server.hint = getString(R.string.vq_server)
        receivers = spinner(listOf(getString(R.string.vq_searching)))
        receivers.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    endpoints.getOrNull(position - 1)?.let { server.setText(it.baseUrl) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        discover = button(R.string.vq_find_receivers, ::findReceivers)
        token = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = getString(R.string.vq_token) }
        upload = button(R.string.vq_upload, ::uploadBatch)
        label(R.string.vq_receiver_note)
        label(R.string.vq_note)
        setContentView(scroll)
        refreshArchives()
        updateEnabled()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED) {
            findReceivers()
        }
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
        uploader.cancel()
        job?.cancel()
        getSharedPreferences("vqmetrix-capture", MODE_PRIVATE).edit()
            .putInt("seconds", duration.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3)
            .putString("bitrates", bitrates.text.toString())
            .putString("codec", if (codec.selectedItemPosition == 0) "h264" else "hevc").apply()
        if (::server.isInitialized) getPreferences(MODE_PRIVATE).edit().putString("receiver", server.text.toString()).apply()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DISCOVERY_REQUEST) {
            discoveryPermissionPending = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                findReceivers()
            } else {
                status.text = getString(R.string.vq_network_permission)
            }
            updateEnabled()
            return
        }
        if (requestCode == NETWORK_REQUEST) {
            networkPermissionPending = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                uploadBatch()
            } else {
                status.text = getString(R.string.vq_network_permission)
            }
            updateEnabled()
            return
        }
        if (requestCode != CAMERA_REQUEST) return
        val config = permissionPending
        permissionPending = null
        if (config != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startBatch(config)
        } else {
            status.text = getString(R.string.vq_permission)
        }
        updateEnabled()
    }

    private fun findReceivers() {
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            discoveryPermissionPending = true
            updateEnabled()
            requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), DISCOVERY_REQUEST)
            return
        }
        discovery.start({ found ->
            val selectedUrl = server.text.toString()
            endpoints = found
            val labels = listOf(getString(if (found.isEmpty()) R.string.vq_searching else R.string.vq_select_receiver)) + found.map { "${it.name} · ${it.baseUrl}" }
            receivers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            receivers.setSelection(found.indexOfFirst { it.baseUrl == selectedUrl } + 1)
        }, { code -> status.text = getString(R.string.vq_discovery_error, code) })
    }

    private fun createBatch() {
        val config =
            try {
                val camera = source.selectedItemPosition == 1
                val hd = resolution.selectedItemPosition == 1
                BatchConfig(
                    if (hd) 1280 else 640,
                    if (hd) {
                        720
                    } else if (camera) {
                        480
                    } else {
                        360
                    },
                    if (fps.selectedItemPosition == 0) 15 else 30,
                    duration.text.toString().toInt(),
                    BatchConfig.parseBitrates(bitrates.text.toString()),
                    if (codec.selectedItemPosition == 0) "h264" else "hevc",
                    camera,
                )
            } catch (_: Exception) {
                status.text = getString(R.string.vq_invalid)
                return
            }
        if (config.camera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = config
            updateEnabled()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            startBatch(config)
        }
    }

    private fun startBatch(config: BatchConfig) {
        setBusy(true)
        job =
            lifecycleScope.launch {
                try {
                    val result = BatchPipeline(this@VQMetrixActivity).create(config, { updateProgress(it) }, { updatePreview(it) })
                    selected = result
                    refreshArchives()
                    status.text = getString(R.string.vq_ready, result.archive.length() / 1_048_576.0, result.runId.take(8))
                } catch (
                    _: CancellationException,
                ) {
                    status.text = getString(R.string.vq_cancelled)
                } catch (
                    failure: Exception,
                ) {
                    status.text = getString(R.string.vq_error, failure.message ?: failure.javaClass.simpleName)
                } finally {
                    setBusy(false)
                    job = null
                }
            }
    }

    private fun uploadBatch() {
        val batch = selected ?: return
        val address = server.text.toString()
        val credential = token.text.toString()
        try {
            BatchUploader.endpoint(address)
            require(credential.isNotBlank())
        } catch (
            failure: Exception,
        ) {
            status.text = getString(R.string.vq_error, failure.message)
            return
        }
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
            networkPermissionPending = true
            updateEnabled()
            requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), NETWORK_REQUEST)
            return
        }
        setBusy(true)
        job =
            lifecycleScope.launch {
                try {
                    val id =
                        uploader.upload(batch, address, credential) { sent, total ->
                            runOnUiThread {
                                progress.progress = (sent * 100 / maxOf(1, total)).toInt()
                                status.text = getString(R.string.vq_upload_progress, progress.progress)
                            }
                        }
                    status.text = getString(R.string.vq_uploaded, id)
                } catch (
                    _: CancellationException,
                ) {
                    status.text = getString(R.string.vq_cancelled)
                } catch (
                    failure: Exception,
                ) {
                    status.text = getString(R.string.vq_error, failure.message ?: failure.javaClass.simpleName)
                } finally {
                    setBusy(false)
                    job = null
                }
            }
    }

    private fun shareBatch() {
        val batch = selected ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.vqmetrix.files", batch.archive)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.vq_share),
            ),
        )
    }

    private fun refreshArchives() {
        archives =
            File(filesDir, "runs").listFiles().orEmpty().filter { File(it, "batch.zip").isFile }
                .sortedByDescending { it.lastModified() }.map { BatchArchive(it.name, it, File(it, "batch.zip")) }
        val labels =
            archives.map { batch ->
                val manifest = runCatching { JSONObject(File(batch.directory, "manifest.json").readText()) }.getOrNull()
                "${batch.runId.take(
                    8,
                )} · ${manifest?.optString("createdAt").orEmpty()} · ${"%.1f".format(batch.archive.length() / 1_048_576.0)} MB"
            }.ifEmpty { listOf(getString(R.string.vq_no_runs)) }
        saved.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = archives.indexOfFirst { it.runId == selected?.runId }.coerceAtLeast(0)
        saved.setSelection(index)
        selected = archives.getOrNull(index)
        updateEnabled()
    }

    private fun updateProgress(value: BatchProgress) =
        runOnUiThread {
            progress.progress = value.current * 100 / maxOf(1, value.total)
            status.text =
                when (value.stage) {
                    "reference" -> getString(R.string.vq_reference_progress, value.current, value.total)
                    "encoding" -> getString(R.string.vq_encoding_progress, value.detail, value.current, value.total)
                    else -> getString(R.string.vq_packaging)
                }
        }

    private fun updatePreview(bitmap: Bitmap) = runOnUiThread { if (!isDestroyed) preview.setImageBitmap(bitmap) }

    private fun setBusy(value: Boolean) {
        busy = value
        if (value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progress.progress = 0
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updateEnabled()
    }

    private fun updateEnabled() {
        if (!::upload.isInitialized) return
        val available = !busy && permissionPending == null && !networkPermissionPending && !discoveryPermissionPending
        listOf<View>(source, resolution, fps, codec, duration, bitrates, generate, language, server, token, saved, discover, receivers).forEach {
            it.isEnabled = available
        }
        share.isEnabled = available && selected != null
        upload.isEnabled = available && selected != null
        cancel.isEnabled = busy
    }

    private fun chooseLanguage() {
        AlertDialog.Builder(this).setTitle(R.string.vq_language)
            .setItems(arrayOf(getString(R.string.vq_system), "English", "日本語")) { _, index ->
                getSharedPreferences("vqmetrix-ui", MODE_PRIVATE).edit().putString("language", arrayOf("", "en", "ja")[index]).apply()
                recreate()
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CAMERA_REQUEST = 401
        private const val NETWORK_REQUEST = 402
        private const val DISCOVERY_REQUEST = 403
    }
}
