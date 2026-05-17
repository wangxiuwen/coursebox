package com.wangxiuwen.coursebox.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ImportResult(
    val packages: List<CoursePackageRecord>,
    val addedObjects: Int,
)

/**
 * App-wide course library. Holds imported packages, materialises objects
 * to a content-addressed store under `filesDir/coursebox_library/`, and
 * persists state to `library_index.json`.
 *
 * Drives Compose recomposition through [stateFlow] (a [mutableStateOf]
 * because that's the lightest way to bridge to Compose without ViewModel
 * indirection — there's only ever one CourseLibrary per process).
 */
class CourseLibrary private constructor(
    private val root: File,
    initial: CourseLibraryState,
) {
    val stateFlow = mutableStateOf(initial)
    val state: CourseLibraryState get() = stateFlow.value

    val objectsDir: File get() = File(root, "objects")
    val packagesDir: File get() = File(root, "packages")

    private val indexFile: File get() = File(root, INDEX_NAME)

    fun packageById(id: String): CoursePackageRecord? =
        state.packages.firstOrNull { it.id == id }

    fun packagesOfType(types: Collection<String>): List<CoursePackageRecord> {
        if (types.isEmpty()) return state.packages
        val set = types.toSet()
        return state.packages.filter { it.type in set }
    }

    fun resolveHash(hash: String): String? {
        val norm = if (hash.startsWith("sha256:")) hash else "sha256:$hash"
        for (pkg in state.packages) {
            val path = pkg.resourceIndex[norm]
            if (path != null && File(path).exists()) return path
        }
        return null
    }

    fun resolveLogicalPath(logical: String): String? {
        val norm = normalizeLogicalPath(logical)
        for (pkg in state.packages) {
            val path = pkg.logicalPathIndex[norm]
            if (path != null && File(path).exists()) return path
        }
        return null
    }

    fun resolve(hash: String? = null, logicalPath: String? = null): String? {
        if (!hash.isNullOrBlank()) resolveHash(hash)?.let { return it }
        if (!logicalPath.isNullOrBlank()) return resolveLogicalPath(logicalPath)
        return null
    }

    suspend fun loadLessons(courseId: String): List<Map<String, kotlinx.serialization.json.JsonElement>> =
        withContext(Dispatchers.IO) {
            val pkg = packageById(courseId) ?: return@withContext emptyList()
            val file = File(pkg.lessonsManifestPath)
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val arr = json.parseToJsonElement(file.readText())
                (arr as? kotlinx.serialization.json.JsonArray)?.map {
                    (it as kotlinx.serialization.json.JsonObject).toMap()
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    suspend fun toggleLearning(courseId: String) {
        val current = state.learning.toMutableSet()
        if (!current.add(courseId)) current.remove(courseId)
        stateFlow.value = state.copy(learning = current)
        persist()
    }

    fun isLearning(courseId: String): Boolean = courseId in state.learning

    suspend fun togglePinned(courseId: String) {
        val current = state.pinned.toMutableList()
        if (current.remove(courseId)) {
            stateFlow.value = state.copy(pinned = current)
        } else {
            current.add(0, courseId)
            stateFlow.value = state.copy(pinned = current)
        }
        persist()
    }

    fun isPinned(courseId: String): Boolean = courseId in state.pinned

    /** Read a logical-path resource as text. Returns null if not imported. */
    suspend fun loadString(logicalPath: String): String? = withContext(Dispatchers.IO) {
        val p = resolveLogicalPath(logicalPath) ?: return@withContext null
        runCatching { File(p).readText() }.getOrNull()
    }

    suspend fun importZip(ctx: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Stream the SAF input to a temp file we can ZipFile-seek on.
        val tmp = File.createTempFile("import-", ".zip", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("无法打开 zip: $uri")
            importLocalFile(tmp, sourceLabel = uri.toString())
        } finally {
            tmp.delete()
        }
    }

    suspend fun importLocalFile(zip: File, sourceLabel: String = zip.absolutePath): ImportResult =
        withContext(Dispatchers.IO) {
            require(zip.exists()) { "课程包不存在: $zip" }
            ZipFile(zip).use { zf ->
                val manifestEntry = zf.getEntry("manifest.json")
                    ?: error("课程包缺少 manifest.json")
                val manifestStr = zf.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                val manifest: CoursePackageManifest = json.decodeFromString(manifestStr)
                require(manifest.format == "parrot-course-package" && manifest.version == 1) {
                    "课程包格式不兼容: ${manifest.format}@v${manifest.version}"
                }

                objectsDir.mkdirs()
                packagesDir.mkdirs()

                var added = 0
                for (entry in zf.entries()) {
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("objects/")) continue
                    val target = File(root, entry.name)
                    if (target.exists()) continue
                    target.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    added++
                }

                val resourceIndex = buildResourceIndex(manifest.resources)
                val logicalIndex = buildLogicalIndex(manifest.resources)

                val now = Instant.now().toString()
                val incomingIds = manifest.courses.map { it.id }.toSet()
                val replacedPackages = state.packages.filter { it.id !in incomingIds }
                val newRecords = manifest.courses.map { c ->
                    CoursePackageRecord(
                        id = c.id,
                        title = c.title,
                        description = c.description,
                        type = c.type,
                        metadata = c.metadata,
                        lessonsManifestPath = File(root, c.lessonsManifest).absolutePath,
                        lessonIndex = c.lessonIndex,
                        resourceIndex = resourceIndex,
                        logicalPathIndex = logicalIndex,
                        importedAt = now,
                        source = "zip:$sourceLabel",
                    )
                }
                stateFlow.value = state.copy(packages = replacedPackages + newRecords)

                val digest = sha256(manifestStr.toByteArray())
                File(packagesDir, "manifest_$digest.json").writeText(manifestStr)
                persist()

                ImportResult(newRecords, added)
            }
        }

    private fun buildResourceIndex(resources: List<CourseResource>): Map<String, String> =
        resources.associate { r ->
            val key = if (r.hash.startsWith("sha256:")) r.hash else "sha256:${r.hash}"
            key to File(root, r.path).absolutePath
        }

    private fun buildLogicalIndex(resources: List<CourseResource>): Map<String, String> =
        resources.filter { it.origin.isNotBlank() }
            .associate { normalizeLogicalPath(it.origin) to File(root, it.path).absolutePath }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        root.mkdirs()
        indexFile.writeText(json.encodeToString(state))
    }

    companion object {
        private const val INDEX_NAME = "library_index.json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        @Volatile private var instance: CourseLibrary? = null

        suspend fun get(ctx: Context): CourseLibrary {
            instance?.let { return it }
            return withContext(Dispatchers.IO) {
                instance ?: build(ctx).also { instance = it }
            }
        }

        private fun build(ctx: Context): CourseLibrary {
            val root = File(ctx.filesDir, "coursebox_library").apply { mkdirs() }
            File(root, "objects").mkdirs()
            File(root, "packages").mkdirs()

            val indexFile = File(root, INDEX_NAME)
            val state = runCatching {
                if (indexFile.exists()) json.decodeFromString<CourseLibraryState>(indexFile.readText())
                else CourseLibraryState()
            }.getOrDefault(CourseLibraryState())
            return CourseLibrary(root, state)
        }

        private fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hex = md.digest(bytes).joinToString("") { "%02x".format(it) }
            return hex
        }
    }
}

/** Hash an InputStream lazily — used by future packers/tests; unused at runtime. */
internal fun InputStream.sha256Hex(): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Quietly close any [ZipInputStream] holders. Kept here so test fixtures share it. */
internal fun ZipInputStream.drain() {
    val buf = ByteArray(8 * 1024)
    while (read(buf) > 0) { /* discard */ }
}
