package com.haishinkit.screen.scene

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
class SceneArchive(
    snapshot: ScreenObjectSnapshot,
    val resources: Map<String, ByteArray> = emptyMap(),
) {
    val snapshot: ScreenObjectSnapshot = portable(snapshot, resources)

    companion object {
        const val MAXIMUM_RESOURCE_BYTES: Int = 128 * 1024 * 1024
        private const val MAXIMUM_JSON_BYTES: Int = 4 * 1024 * 1024
        private const val MAXIMUM_ARCHIVE_BYTES: Int = MAXIMUM_RESOURCE_BYTES + 8 * 1024 * 1024

        /** Reads the shared ZIP, or a standalone snapshot JSON without external resources. */
        fun read(bytes: ByteArray): SceneArchive {
            require(bytes.size <= MAXIMUM_ARCHIVE_BYTES) { "Scene archive exceeds its size limit." }
            if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
                require(bytes.size <= MAXIMUM_JSON_BYTES)
                return SceneArchive(Json.decodeFromString<ScreenObjectSnapshot>(bytes.toString(Charsets.UTF_8)))
            }
            val files = mutableMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            var total = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    require(names.add(entry.name) && names.size <= 260) { "Duplicate or excessive ZIP entries." }
                    if (entry.isDirectory) {
                        require(entry.name == "media/") { "Unexpected scene directory." }
                        input.closeEntry()
                        continue
                    }
                    require(
                        entry.name == "scene.json" || entry.name == "manifest.json" ||
                            (entry.name.startsWith("media/") && validName(entry.name.removePrefix("media/"))),
                    ) { "Invalid scene entry." }
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count == -1) break
                        total += count
                        require(total <= MAXIMUM_ARCHIVE_BYTES) { "Scene archive exceeds its size limit." }
                        buffer.write(chunk, 0, count)
                    }
                    files[entry.name] = buffer.toByteArray()
                    input.closeEntry()
                }
            }
            val json = requireNotNull(files.remove("scene.json")) { "Missing scene.json." }
            require(json.size <= MAXIMUM_JSON_BYTES)
            files.remove("manifest.json")?.let {
                require(it.size <= 65536)
                val manifest = Json.decodeFromString<Manifest>(it.toString(Charsets.UTF_8))
                require(manifest.format == "haishinkit.scene" && manifest.version == 1) { "Unsupported scene version." }
            }
            return SceneArchive(
                Json.decodeFromString<ScreenObjectSnapshot>(json.toString(Charsets.UTF_8)),
                files.mapKeys { it.key.removePrefix("media/") },
            )
        }

        private fun portable(
            node: ScreenObjectSnapshot,
            resources: Map<String, ByteArray>,
        ): ScreenObjectSnapshot {
            val elements = node.elements.toMutableMap()
            if (node.type == "image" || node.type == "asset") {
                val source = elements["source"]
                if (source != null && source.startsWith("file:", ignoreCase = true)) {
                    val name = java.io.File(java.net.URI(source)).name
                    if (resources.containsKey(name)) elements["source"] = "media/$name"
                }
            }
            return node.copy(elements = elements, children = node.children.map { portable(it, resources) })
        }

        private fun validName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000')
    }

    init {
        require(resources.size <= 256 && resources.keys.all { validName(it) }) { "Invalid scene resource names." }
        require(resources.values.sumOf { it.size.toLong() } <= MAXIMUM_RESOURCE_BYTES) { "Scene resources exceed 128 MB." }

        fun validate(node: ScreenObjectSnapshot) {
            if (node.type == "image" || node.type == "asset") {
                val source = node.elements["source"] ?: ""
                if (!(node.type == "image" && source.startsWith("data:"))) {
                    require(source.startsWith("media/") && validName(source.removePrefix("media/")) && resources.containsKey(source.removePrefix("media/"))) { "Missing or invalid packaged media." }
                }
            }
            node.children.forEach { validate(it) }
        }
        validate(this.snapshot)
    }

    /** Writes ZIP DEFLATE entries compatible with SceneArchive on Apple platforms. */
    fun write(): ByteArray {
        val validated = SceneArchive(snapshot, resources.toMap())
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
