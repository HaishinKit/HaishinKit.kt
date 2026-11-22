package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable Apple/Android scene file containing `scene.json`, `manifest.json`, and `media/<filename>`.
 *
 * The snapshot uses camelCase property names. Resource paths are relative to this ZIP; callers
 * materialize resources into their own app storage before supplying media URLs to screen objects.
 * Unsupported objects are invisible and retain their original snapshot when recaptured.
 */
class SceneFile(
    snapshot: ScreenObjectSnapshot,
    val resources: Map<String, ByteArray> = emptyMap(),
) {
    val snapshot: ScreenObjectSnapshot = portable(snapshot, resources)

    companion object {
        const val MAXIMUM_RESOURCE_BYTES: Int = 128 * 1024 * 1024
        private const val MAXIMUM_JSON_BYTES: Int = 4 * 1024 * 1024
        private const val MAXIMUM_ARCHIVE_BYTES: Int = MAXIMUM_RESOURCE_BYTES + 8 * 1024 * 1024

        /** Reads the shared ZIP, or a standalone snapshot JSON without external resources. */
        fun read(bytes: ByteArray): SceneFile {
            require(bytes.size <= MAXIMUM_ARCHIVE_BYTES) { "Scene file exceeds its size limit." }
            if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
                require(bytes.size <= MAXIMUM_JSON_BYTES)
                return SceneFile(Json.decodeFromString<ScreenObjectSnapshot>(bytes.toString(Charsets.UTF_8)))
            }
            val directory = directory(bytes)
            val files = mutableMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            var total = 0
            var resourceBytes = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(names.add(entry.name) && names.size <= 260) { "Duplicate or excessive ZIP entries." }
                    val declared = requireNotNull(directory[entry.name]) { "Entry missing from central directory." }
                    if (entry.isDirectory) {
                        require(entry.name == "media/") { "Unexpected scene directory." }
                        require(input.read() == -1 && declared.size == 0L) { "Nonempty directory." }
                        input.closeEntry()
                        continue
                    }
                    require(
                        entry.name == "scene.json" || entry.name == "manifest.json" ||
                            (entry.name.startsWith("media/") && validName(entry.name.removePrefix("media/"))),
                    ) { "Invalid scene entry." }
                    val limit =
                        when (entry.name) {
                            "scene.json" -> MAXIMUM_JSON_BYTES
                            "manifest.json" -> 65536
                            else -> MAXIMUM_RESOURCE_BYTES - resourceBytes
                        }
                    require(declared.size <= limit.toLong()) { "Scene entry exceeds its size limit." }
                    val data = ByteArray(declared.size.toInt())
                    var position = 0
                    while (position < data.size) {
                        val count = input.read(data, position, data.size - position)
                        require(count > 0) { "Truncated scene entry." }
                        position += count
                        total += count
                        require(total <= MAXIMUM_ARCHIVE_BYTES) { "Scene file exceeds its size limit." }
                    }
                    require(input.read() == -1 && entry.crc == declared.crc && entry.compressedSize == declared.packed && entry.method == declared.method)
                    if (entry.name.startsWith("media/")) resourceBytes += data.size
                    files[entry.name] = data
                    input.closeEntry()
                }
            }
            require(names == directory.keys) { "Missing ZIP entries." }
            val json = requireNotNull(files.remove("scene.json")) { "Missing scene.json." }
            require(json.size <= MAXIMUM_JSON_BYTES)
            files.remove("manifest.json")?.let {
                require(it.size <= 65536)
                val manifest = Json.decodeFromString<Manifest>(it.toString(Charsets.UTF_8))
                require(manifest.format == "haishinkit.scene" && manifest.version == 1) { "Unsupported scene version." }
            }
            return SceneFile(
                Json.decodeFromString<ScreenObjectSnapshot>(json.toString(Charsets.UTF_8)),
                files.mapKeys { it.key.removePrefix("media/") },
            )
        }

        private fun portable(
            node: ScreenObjectSnapshot,
            resources: Map<String, ByteArray>,
        ): ScreenObjectSnapshot {
            if (node.type !in setOf("screen", "container", "video", "image", "text", "asset")) return node
            val elements = node.elements.toMutableMap()
            if (node.type == "video" && !elements.containsKey("videoGravity")) elements["videoGravity"] = "resizeAspect"
            if (node.type == "image" || node.type == "asset") {
                val source = elements["source"]
                if (source != null && source.startsWith("file:", ignoreCase = true)) {
                    val name = java.io.File(java.net.URI(source).path).name
                    if (resources.containsKey(name)) elements["source"] = "media/$name"
                }
            }
            if ((node.type == "image" || node.type == "asset") && elements["source"]?.startsWith("media/") == true) {
                val name = elements.getValue("source").removePrefix("media/")
                resources.keys.firstOrNull { canonical(it) == canonical(name) }?.let { elements["source"] = "media/$it" }
            }
            return node.copy(elements = elements, children = node.children.map { portable(it, resources) })
        }

        // Validate the complete ZIP before the streaming reader can accept a truncated prefix.
        private fun directory(bytes: ByteArray): Map<String, ZipInfo> {
            require(bytes.size >= 22)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            fun u16(offset: Int): Int = input.getShort(offset).toInt() and 0xffff

            fun u32(offset: Int): Long = input.getInt(offset).toLong() and 0xffffffffL
            val end =
                (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
                    u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
                } ?: throw IllegalArgumentException("Missing ZIP directory.")
            require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10))
            val count = u16(end + 10)
            val start = u32(end + 16)
            require(count <= 260 && start + u32(end + 12) == end.toLong())
            var cursor = start.toInt()
            val result = mutableMapOf<String, ZipInfo>()
            repeat(count) {
                require(cursor >= 0 && cursor <= end - 46 && u32(cursor) == 0x02014b50L)
                val length = u16(cursor + 28)
                val next = cursor.toLong() + 46 + length + u16(cursor + 30) + u16(cursor + 32)
                require(next <= end && u16(cursor + 34) == 0 && u32(cursor + 42) < start)
                val name = bytes.copyOfRange(cursor + 46, cursor + 46 + length).toString(Charsets.UTF_8)
                val offset = u32(cursor + 42).toInt()
                val packed = u32(cursor + 20)
                val size = u32(cursor + 24)
                val crc = u32(cursor + 16)
                val method = u16(cursor + 10)
                require(offset >= 0 && offset <= start - 30 && u32(offset) == 0x04034b50L)
                require(method in setOf(0, 8) && u16(offset + 8) == method && u16(offset + 6) == u16(cursor + 8) && u16(offset + 6) and 1 == 0)
                val localLength = u16(offset + 26)
                val dataOffset = offset.toLong() + 30 + localLength + u16(offset + 28)
                require(dataOffset + packed <= start && localLength == length)
                require(bytes.copyOfRange(offset + 30, offset + 30 + localLength).contentEquals(bytes.copyOfRange(cursor + 46, cursor + 46 + length)))
                if (u16(offset + 6) and 8 == 0) require(u32(offset + 14) == crc && u32(offset + 18) == packed && u32(offset + 22) == size)
                require(result.put(name, ZipInfo(crc, size, packed, method)) == null)

                cursor = next.toInt()
            }
            require(cursor == end)
            return result
        }

        private data class ZipInfo(val crc: Long, val size: Long, val packed: Long, val method: Int)

        private fun canonical(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

        // Conservative portable case key, also available on Android API 23 (before platform ICU).
        private fun caseKey(name: String): String = canonical(canonical(name).lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT))

        private fun validName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000')
    }

    init {
        require(resources.keys.map { caseKey(it) }.toSet().size == resources.size) { "Colliding resource names." }
        require(resources.size <= 256 && resources.keys.all { validName(it) }) { "Invalid scene resource names." }
        require(resources.values.sumOf { it.size.toLong() } <= MAXIMUM_RESOURCE_BYTES) { "Scene resources exceed 128 MB." }

        fun validate(node: ScreenObjectSnapshot) {
            if (node.type == "image" || node.type == "asset") {
                val source = node.elements["source"] ?: ""
                if (!(node.type == "image" && source.startsWith("data:"))) {
                    require(source.startsWith("media/") && validName(source.removePrefix("media/")) && resources.keys.any { canonical(it) == canonical(source.removePrefix("media/")) }) { "Missing or invalid packaged media." }
                }
            }
            node.children.forEach { validate(it) }
        }
        validate(this.snapshot)
    }

    /** Writes ZIP DEFLATE entries compatible with SceneFile on Apple platforms. */
    fun write(): ByteArray {
        val validated = SceneFile(snapshot, resources.toMap())
        val snapshotJSON = Json.encodeToString(validated.snapshot).toByteArray(Charsets.UTF_8)
        require(snapshotJSON.size <= MAXIMUM_JSON_BYTES)
        val files =
            mutableMapOf(
                "scene.json" to snapshotJSON,
                "manifest.json" to Json.encodeToString(Manifest("haishinkit.scene", 1)).toByteArray(Charsets.UTF_8),
            )
        for ((name, data) in validated.resources) files["media/$name"] = data
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in files.toSortedMap()) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray().also { require(it.size <= MAXIMUM_ARCHIVE_BYTES) }
    }
}

@Serializable
private data class Manifest(val format: String, val version: Int)
