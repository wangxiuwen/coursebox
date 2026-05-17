package com.wangxiuwen.coursebox.packager.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Scans a source folder, hashes every file, and emits a
 * `.coursebox.zip` whose manifest references each file by content hash.
 *
 * The packager is content-agnostic: callers describe one or more
 * "courses", each pointing at a folder of resources and (optionally) a
 * `lessons.json` describing the per-lesson detail. The folder layout
 * convention is just "anything that's not lessons.json is a resource";
 * callers can override that by passing explicit `resources` /
 * `lessonsFile` paths.
 */
object Packager {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Author-supplied course definition. One [PackageBuildSpec] usually
     * contains one course, but the manifest format supports multiple.
     */
    data class CourseSpec(
        val id: String,
        val title: String,
        val description: String,
        val type: String,
        /** Folder that holds the lessons.json + audio/pdf resources. */
        val sourceDir: File,
        /** Override path to the lessons.json. Defaults to `sourceDir/lessons.json`. */
        val lessonsFile: File? = null,
        /** Free-form per-course metadata, copied to the manifest. */
        val metadata: Map<String, JsonElement> = emptyMap(),
    )

    data class BuildResult(
        val outputZip: File,
        val resourceCount: Int,
        val courseCount: Int,
        val totalBytes: Long,
        val warnings: List<String>,
    )

    suspend fun build(
        courses: List<CourseSpec>,
        outputZip: File,
        generator: String = "coursebox-packager",
        progress: ((PackagerProgress) -> Unit)? = null,
    ): BuildResult = withContext(Dispatchers.IO) {
        require(courses.isNotEmpty()) { "至少需要一个课程" }
        outputZip.parentFile?.mkdirs()

        val warnings = mutableListOf<String>()

        // 1) Hash every resource. Same byte content used by two courses
        //    is recorded once thanks to the hash → entry map.
        val resourcesByHash = LinkedHashMap<String, ResourceBuild>()
        val perCourseResources = mutableMapOf<String, MutableList<String>>() // courseId → hashes
        val perCourseLessonsHash = mutableMapOf<String, String>()
        val perCourseLessonIndex = mutableMapOf<String, List<LessonIndexEntry>>()

        var processed = 0L

        for ((idx, course) in courses.withIndex()) {
            progress?.invoke(PackagerProgress.CourseStart(idx, courses.size, course.title))

            val lessonsFile = course.lessonsFile
                ?: File(course.sourceDir, "lessons.json").takeIf { it.exists() }
                ?: error("课程 '${course.title}' 缺少 lessons.json (sourceDir=${course.sourceDir})")

            require(lessonsFile.exists()) { "lessons.json 不存在: $lessonsFile" }

            val lessonBytes = lessonsFile.readBytes()
            val lessonsHash = sha256Hex(lessonBytes)
            val lessonsEntryPath = "objects/$lessonsHash.json"
            resourcesByHash.getOrPut(lessonsHash) {
                ResourceBuild(
                    hash = "sha256:$lessonsHash",
                    path = lessonsEntryPath,
                    bytes = lessonBytes,
                    type = "application/json",
                    origin = relativiseInside(course.sourceDir, lessonsFile),
                    tags = listOf("lessons", "course:${course.id}"),
                )
            }
            perCourseLessonsHash[course.id] = lessonsHash
            perCourseLessonIndex[course.id] = parseLessonIndex(lessonBytes)

            // Walk sourceDir for all the other files
            val resourceList = mutableListOf<String>()
            course.sourceDir.walkTopDown()
                .filter { it.isFile && it != lessonsFile }
                .filter { !it.name.startsWith(".") }
                .forEach { file ->
                    val bytes = file.readBytes()
                    val hash = sha256Hex(bytes)
                    resourcesByHash.getOrPut(hash) {
                        ResourceBuild(
                            hash = "sha256:$hash",
                            path = "objects/$hash${file.extension.let { if (it.isBlank()) "" else ".$it" }}",
                            bytes = bytes,
                            type = guessMimeType(file.name),
                            origin = relativiseInside(course.sourceDir, file),
                            tags = listOf("course:${course.id}"),
                        )
                    }
                    resourceList += hash
                    processed += bytes.size.toLong()
                    progress?.invoke(PackagerProgress.FileHashed(file.name, processed))
                }
            perCourseResources[course.id] = resourceList
        }

        // 2) Cross-link lesson_index audio_hash by matching origin → hash
        //    when the JSON references audio by `audio_local` rather than
        //    `audio_hash` directly. (Author convenience.)
        val originIndex = resourcesByHash.values.associateBy { it.origin }
        val updatedIndices = perCourseLessonIndex.mapValues { (_, entries) ->
            entries.map { entry ->
                if (entry.audioHash.isNotBlank()) entry
                else {
                    val origin = (entry.metadata["audio_local"] as? JsonPrimitive)?.contentOrNull
                        ?: (entry.metadata["audio_path"] as? JsonPrimitive)?.contentOrNull
                    val match = origin?.let { originIndex[normalizeLogical(it)] }
                    if (match != null) entry.copy(audioHash = match.hash) else entry
                }
            }
        }

        // 3) Build manifest
        val manifest = CoursePackageManifest(
            generatedAt = Instant.now().toString(),
            generator = generator,
            resources = resourcesByHash.values
                .sortedBy { it.hash }
                .map { it.toResource() },
            courses = courses.map { c ->
                CourseEntry(
                    id = c.id,
                    title = c.title,
                    description = c.description,
                    type = c.type,
                    lessonsManifest = "objects/${perCourseLessonsHash.getValue(c.id)}.json",
                    lessonIndex = updatedIndices.getValue(c.id),
                    metadata = c.metadata,
                )
            },
        )

        // 4) Write zip
        ZipOutputStream(outputZip.outputStream().buffered()).use { zout ->
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            zout.putNextEntry(ZipEntry("manifest.json"))
            zout.write(manifestBytes)
            zout.closeEntry()

            for (resource in resourcesByHash.values.sortedBy { it.hash }) {
                zout.putNextEntry(ZipEntry(resource.path))
                zout.write(resource.bytes)
                zout.closeEntry()
            }
        }

        BuildResult(
            outputZip = outputZip,
            resourceCount = resourcesByHash.size,
            courseCount = courses.size,
            totalBytes = resourcesByHash.values.sumOf { it.bytes.size.toLong() },
            warnings = warnings,
        )
    }

    private fun parseLessonIndex(bytes: ByteArray): List<LessonIndexEntry> {
        val node = runCatching {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val arr = node as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            LessonIndexEntry(
                id = id,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                subtitle = (obj["subtitle"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                audioHash = (obj["audio_hash"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                tags = emptyList(),
                metadata = obj,
            )
        }
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            else -> "application/octet-stream"
        }
    }

    private fun relativiseInside(root: File, file: File): String {
        val rel = file.toPath().toAbsolutePath()
            .normalize()
            .toString()
            .removePrefix(root.toPath().toAbsolutePath().normalize().toString())
            .trimStart(File.separatorChar)
        return rel.replace(File.separatorChar, '/')
    }

    private fun normalizeLogical(raw: String): String {
        var p = raw.replace('\\', '/').trim()
        if (p.startsWith("./")) p = p.substring(2)
        if (p.startsWith("assets/")) p = p.substring("assets/".length)
        while (p.startsWith("/")) p = p.substring(1)
        return p
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class ResourceBuild(
        val hash: String,
        val path: String,
        val bytes: ByteArray,
        val type: String,
        val origin: String,
        val tags: List<String>,
    ) {
        fun toResource() = CourseResource(
            hash = hash,
            path = path,
            size = bytes.size.toLong(),
            type = type,
            origin = origin,
            tags = tags,
        )
    }
}

sealed class PackagerProgress {
    data class CourseStart(val index: Int, val total: Int, val title: String) : PackagerProgress()
    data class FileHashed(val name: String, val bytesProcessed: Long) : PackagerProgress()
}
