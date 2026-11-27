package com.haishinkit.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.ScreenObjectFactory
import com.haishinkit.screen.scene.SceneFile
import com.haishinkit.screen.scene.ScreenObjectSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Sample integration of the library's portable .scene format. No capture or connection credentials. */
internal class SampleSceneFiles(private val context: Context) {
    private var resources: Map<String, ByteArray> = emptyMap()
    private var animations: Map<String, ByteArray> = emptyMap()
    private var directory: File? = null

    fun encode(snapshot: ScreenObjectSnapshot): ByteArray {
        val media = resources.toMutableMap()

        fun portable(node: ScreenObjectSnapshot): ScreenObjectSnapshot {
            val children = node.children.map(::portable)
            if (node.type != LottieScreen.TYPE) return node.copy(children = children)
            val animation =
                animations[node.id] ?: context.resources.openRawResource(
                    requireNotNull(node.elements["animationResId"]?.toIntOrNull()?.takeIf { it != 0 }),
                ).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(animation).joinToString("") { "%02x".format(it) }
            val name = "animation-$digest.json"
            media[name] = animation
            return node.copy(type = "asset", elements = mapOf("source" to "media/$name", "format" to "lottie"), children = children)
        }
        return SceneFile(portable(snapshot), media).write().also { require(it.size <= MAXIMUM_FILE_BYTES) { "Sample scene files must be 32 MiB or smaller." } }
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
        return prepare(SceneFile.read(bytes))
    }

    internal fun prepare(file: SceneFile): Loaded {
        val root = file.snapshot
        require(root.type == "screen" || root.type == "container") { "Choose a scene with a screen or container root." }
        require(root.size.width in 1..4096 && root.size.height in 1..4096 && root.size.width.toLong() * root.size.height <= 8_388_608) { "Scene canvas exceeds the sample's supported size." }
        val folder = File(context.cacheDir, "scene-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            file.resources.forEach { (name, data) -> File(folder, name).writeBytes(data) }
            val names = mutableSetOf<String>()
            var pixels = 0L
            var count = 0

            fun resolve(
                node: ScreenObjectSnapshot,
                depth: Int,
            ): ScreenObjectSnapshot {
                require(depth <= 32 && ++count <= 256 && names.add(node.id)) { "Scene has duplicate IDs or too many objects." }
                var elements = node.elements
                if (node.type == "image") {
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
                return node.copy(elements = elements, children = node.children.map { resolve(it, depth + 1) })
            }
            return Loaded(file, resolve(root, 0), folder)
        } catch (cause: Throwable) {
            folder.deleteRecursively()
            throw cause
        }
    }

    fun create(loaded: Loaded): ScreenObjectContainer {
        val fallback = ScreenObjectFactory()
        val factory = ScreenObjectFactory()
        factory.register("asset") { node ->
            if (node.elements["format"] != "lottie") {
                fallback.create(node)
            } else {
                val name = node.elements.getValue("source").removePrefix("media/")
                val json = requireNotNull(loaded.file.resources[name]).toString(Charsets.UTF_8)
                LottieScreen(context, node.id).apply {
                    setAnimationFromJson(json)
                    playAnimation()
                }
            }
        }
        return factory.create(loaded.snapshot) as ScreenObjectContainer
    }

    fun install(loaded: Loaded) {
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

    private companion object {
        const val MAXIMUM_FILE_BYTES = 32 * 1024 * 1024
    }
}
