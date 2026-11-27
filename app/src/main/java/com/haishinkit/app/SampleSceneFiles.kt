package com.haishinkit.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.airbnb.lottie.LottieCompositionFactory
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.ScreenObjectFactory
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.screen.scene.SceneFile
import com.haishinkit.screen.scene.ScreenObjectSnapshot
import com.haishinkit.screen.scene.ScreenObjectSnapshotFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Sample integration of the library's portable .scene format. No capture or connection credentials. */
internal class SampleSceneFiles(private val context: Context) {
    private var resources: Map<String, ByteArray> = emptyMap()
    private var capturedLottieIds: Set<String> = emptySet()
    private var animations: Map<String, ByteArray> = emptyMap()
    private var directory: File? = null

    /** Store frame origins as margins because the portable format records frame size only. */
    fun capture(root: ScreenObject): ScreenObjectSnapshot {
        val liveLottie = mutableSetOf<String>()

        fun positions(
            objectValue: ScreenObject,
            node: ScreenObjectSnapshot,
        ): ScreenObjectSnapshot {
            if (objectValue is LottieScreen) liveLottie.add(objectValue.id)
            val margin = node.layoutMargin.copy()
            if (objectValue.horizontalAlignment == ScreenObject.HORIZONTAL_ALIGNMENT_LEFT) margin.left += objectValue.frame.left
            if (objectValue.verticalAlignment == ScreenObject.VERTICAL_ALIGNMENT_TOP) margin.top += objectValue.frame.top
            val children =
                if (objectValue is ScreenObjectContainer) {
                    objectValue.getChildren().zip(node.children) { child, snapshot -> positions(child, snapshot) }
                } else {
                    node.children
                }
            return node.copy(layoutMargin = margin, children = children)
        }
        val snapshot = positions(root, ScreenObjectSnapshotFactory().create(root))
        capturedLottieIds = liveLottie
        return snapshot
    }

    suspend fun captureNextFrame(
        screen: Screen,
        timeoutMillis: Long = 5000,
    ): ScreenObjectSnapshot {
        val result = CompletableDeferred<ScreenObjectSnapshot>()
        val captured = AtomicBoolean()
        val callback =
            object : Screen.Callback() {
                override fun onEnterFrame() {
                    if (!captured.compareAndSet(false, true)) return
                    runCatching { capture(screen) }.onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
                }
            }
        screen.registerCallback(callback)
        try {
            return withTimeoutOrNull(timeoutMillis) { result.await() } ?: error("The renderer did not provide a scene snapshot.")
        } finally {
            screen.unregisterCallback(callback)
        }
    }

    fun encode(snapshot: ScreenObjectSnapshot): ByteArray {
        val media = resources.toMutableMap()

        fun portable(node: ScreenObjectSnapshot): ScreenObjectSnapshot {
            val liveAnimation = node.type == LottieScreen.TYPE && (node.id in capturedLottieIds || node.id in animations)
            if (!liveAnimation && !supported(node)) return node
            val children = node.children.map(::portable)
            if (videoAsset(node)) {
                val source = node.elements["source"].orEmpty()
                if (source.startsWith("media/")) return node.copy(children = children)
                val uri = Uri.parse(source)
                val installedName = directory?.let { folder -> resources.keys.firstOrNull { uri == Uri.fromFile(File(folder, it)) } }
                if (installedName != null) return node.copy(elements = node.elements + ("source" to "media/$installedName"), children = children)
                require(uri.scheme in setOf("file", "content", "android.resource")) { "Use a local video source." }
                val bytes =
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= MAXIMUM_FILE_BYTES) { "Video asset exceeds 32 MiB." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                val extension = uri.lastPathSegment?.substringAfterLast('.', "bin")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val name = "video-$digest.$extension"
                media[name] = bytes
                return node.copy(elements = node.elements + ("source" to "media/$name"), children = children)
            }
            if (!liveAnimation) return node.copy(children = children)
            val animation =
                animations[node.id] ?: context.resources.openRawResource(
                    requireNotNull(node.elements["animationResId"]?.toIntOrNull()?.takeIf { it != 0 }),
                ).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(animation).joinToString("") { "%02x".format(it) }
            val name = "animation-$digest.json"
            media[name] = animation
            return node.copy(type = "asset", elements = mapOf("source" to "media/$name", "format" to "lottie"), children = children)
        }
        val file = SceneFile(portable(snapshot), media)
        val referenced = mutableSetOf<String>()
        var opaque = false

        fun collect(node: ScreenObjectSnapshot) {
            if (!supported(node)) opaque = true
            node.elements["source"]?.takeIf { it.startsWith("media/") }?.let { referenced.add(it.removePrefix("media/")) }
            node.children.forEach(::collect)
        }
        collect(file.snapshot)
        val bytes = SceneFile(file.snapshot, media.filterKeys { opaque || it in referenced }).write()
        require(bytes.size <= MAXIMUM_FILE_BYTES) { "Sample scene files must be 32 MiB or smaller." }
        // Share import admission rules so a successful export can be opened by this sample.
        decode(bytes).directory.deleteRecursively()
        return bytes
    }

    fun write(
        uri: Uri,
        bytes: ByteArray,
    ) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Could not open the scene file for writing." }.use { it.write(bytes) }
    }

    fun read(uri: Uri): Loaded {
        val bytes =
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the scene file." }.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAXIMUM_FILE_BYTES) { "Sample scene files must be 32 MiB or smaller." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        return decode(bytes)
    }

    internal fun prepare(file: SceneFile): Loaded {
        require(file.resources.values.sumOf { it.size.toLong() } <= MAXIMUM_FILE_BYTES) { "Sample scene resources must be 32 MiB or smaller." }
        val root = file.snapshot
        require(root.type == "screen" || root.type == "container") { "Choose a scene with a screen or container root." }
        require(root.size.width in 1..4096 && root.size.height in 1..4096 && root.size.width.toLong() * root.size.height <= 8_388_608) { "Scene canvas exceeds the sample's supported size." }
        val folder = File(context.cacheDir, "scene-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            file.resources.forEach { (name, data) -> File(folder, name).writeBytes(data) }
            val names = mutableSetOf<String>()
            var pixels = 0L
            var count = 0
            var renderedPixels = 0L
            var videos = 0

            fun resolve(
                node: ScreenObjectSnapshot,
                depth: Int,
                parentWidth: Long,
                parentHeight: Long,
                materialize: Boolean,
            ): ScreenObjectSnapshot {
                require(depth <= 32 && ++count <= 256 && names.add(node.id)) { "Scene has duplicate IDs or too many objects." }
                val width = if (node.size.width > 0) node.size.width.toLong() else (parentWidth - node.layoutMargin.left - node.layoutMargin.right + node.size.width).coerceAtLeast(0)
                val height = if (node.size.height > 0) node.size.height.toLong() else (parentHeight - node.layoutMargin.top - node.layoutMargin.bottom + node.size.height).coerceAtLeast(0)
                val drawable = materialize && supported(node)
                if (drawable && (node.type == "text" || (node.type == "asset" && node.elements["format"] == "lottie"))) {
                    var bitmapWidth = width
                    var bitmapHeight = height
                    if (node.type == "text") {
                        require(parentWidth in 0..Int.MAX_VALUE.toLong() && parentHeight in 0..Int.MAX_VALUE.toLong())
                        val font = node.elements["fontSize"]?.toFloatOrNull() ?: 32f
                        require(font.isFinite() && font in 0f..4096f) { "Unsupported text size." }
                        // Match TextScreenObject's glyph measurement, rather than counting its auto-sized parent area.
                        val paint =
                            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                                typeface = Typeface.DEFAULT_BOLD
                                textSize = font
                            }
                        val availableWidth = width.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

                        @Suppress("DEPRECATION")
                        val textLayout = StaticLayout(node.elements["value"].orEmpty(), paint, availableWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
                        val measuredWidth = (0 until textLayout.lineCount).maxOfOrNull { kotlin.math.ceil(textLayout.getLineWidth(it)).toLong() } ?: 0L
                        bitmapWidth = minOf(measuredWidth, availableWidth.toLong()).coerceAtLeast(1)
                        bitmapHeight = minOf(textLayout.height.toLong(), height).coerceAtLeast(1)
                    }
                    require(bitmapWidth in 1..4096 && bitmapHeight in 1..4096) { "Scene has unsupported bitmap layer dimensions." }
                    renderedPixels += bitmapWidth * bitmapHeight
                    require(renderedPixels <= 8_388_608) { "Scene bitmap layers exceed the sample's supported size." }
                }
                if (drawable && node.type == "asset" && node.elements["format"] == "lottie") {
                    val bytes = requireNotNull(file.resources[node.elements.getValue("source").removePrefix("media/")])
                    requireNotNull(LottieCompositionFactory.fromJsonStringSync(bytes.toString(Charsets.UTF_8), animationKey(bytes)).value) { "Scene contains an unreadable animation." }
                }
                if (drawable && videoAsset(node)) {
                    require(++videos <= 4) { "The sample supports up to four video assets." }
                }
                var elements = node.elements
                if (drawable && videoAsset(node)) {
                    val source = node.elements["source"].orEmpty()
                    require(source.startsWith("media/")) { "Package video files in the scene media directory." }
                    val video = File(folder, source.removePrefix("media/"))
                    require(video.isFile) { "Missing video resource." }
                    elements = elements + ("source" to Uri.fromFile(video).toString())
                }
                if (drawable && node.type == "image") {
                    val source = node.elements["source"].orEmpty()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (source.startsWith("media/")) {
                        val image = File(folder, source.removePrefix("media/"))
                        BitmapFactory.decodeFile(image.path, options)
                        elements = elements + ("source" to Uri.fromFile(image).toString())
                    } else {
                        require(source.startsWith("data:") && source.contains("base64,"))
                        val image = Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(image, 0, image.size, options)
                    }
                    require(options.outWidth > 0 && options.outHeight > 0) { "Scene contains an unreadable image." }
                    pixels += options.outWidth.toLong() * options.outHeight
                    require(pixels <= 16_777_216) { "Scene images exceed the sample's supported size." }
                }
                return node.copy(elements = elements, children = node.children.map { resolve(it, depth + 1, width, height, drawable) })
            }
            return Loaded(file, resolve(root, 0, root.size.width.toLong(), root.size.height.toLong(), true), folder)
        } catch (cause: Throwable) {
            folder.deleteRecursively()
            throw cause
        }
    }

    fun create(loaded: Loaded): ScreenObjectContainer {
        val fallback = ScreenObjectFactory(context)
        val factory = ScreenObjectFactory(context)
        factory.register("asset") { node ->
            if (node.elements["format"] != "lottie") {
                fallback.create(node)
            } else {
                val name = node.elements.getValue("source").removePrefix("media/")
                val json = requireNotNull(loaded.file.resources[name]).toString(Charsets.UTF_8)
                LottieScreen(context, node.id).apply {
                    setAnimationFromJson(json, animationKey(loaded.file.resources.getValue(name)))
                }
            }
        }
        return factory.create(loaded.snapshot) as ScreenObjectContainer
    }

    fun editableObjects(container: ScreenObjectContainer): List<ScreenObject> {
        fun fullCanvas(value: ScreenObject): Boolean =
            value.frame.left == 0 && value.frame.top == 0 &&
                value.frame.width() in listOf(0, container.frame.width()) && value.frame.height() in listOf(0, container.frame.height()) &&
                value.layoutMargin == com.haishinkit.screen.EdgeInsets(0, 0, 0, 0)

        fun cameraBackground(value: ScreenObject): Boolean =
            when (value) {
                is VideoScreenObject -> value !is com.haishinkit.screen.AssetScreenObject && value.track == 0 && fullCanvas(value)
                is ScreenObjectContainer -> fullCanvas(value) && value.getChildren().singleOrNull()?.let { fullCanvas(it) && cameraBackground(it) } == true
                else -> false
            }
        return container.getChildren().filterNot(::cameraBackground)
    }

    fun install(loaded: Loaded) {
        capturedLottieIds = emptySet()
        val previous = directory
        directory = loaded.directory
        resources = loaded.file.resources
        val sources = mutableMapOf<String, ByteArray>()

        fun collect(node: ScreenObjectSnapshot) {
            if (node.type == "asset" && node.elements["format"] == "lottie") {
                loaded.file.resources[node.elements["source"].orEmpty().removePrefix("media/")]?.let { sources[node.id] = it }
            }
            node.children.forEach(::collect)
        }
        collect(loaded.file.snapshot)
        animations = sources
        previous?.deleteRecursively()
    }

    fun close() {
        directory?.deleteRecursively()
    }

    internal data class Loaded(val file: SceneFile, val snapshot: ScreenObjectSnapshot, val directory: File)

    private fun decode(bytes: ByteArray): Loaded {
        // Check expansion before the library reader allocates complete resource arrays.
        ZipInputStream(bytes.inputStream()).use { zip ->
            val buffer = ByteArray(8192)
            var expanded = 0L
            while (zip.nextEntry != null) {
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= MAXIMUM_FILE_BYTES) { "Expanded sample scene exceeds 32 MiB." }
                }
            }
        }
        return prepare(SceneFile.read(bytes))
    }

    private fun supported(node: ScreenObjectSnapshot): Boolean = node.type in setOf("screen", "container", "video", "image", "text") || (node.type == "asset" && node.elements["format"] == "lottie") || videoAsset(node)

    private fun videoAsset(node: ScreenObjectSnapshot): Boolean = node.type == "asset" && node.elements["format"] in listOf(null, "video")

    private fun animationKey(bytes: ByteArray): String = "sample-scene-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAXIMUM_FILE_BYTES = 32 * 1024 * 1024
    }
}
