package com.wangxiuwen.coursebox.ui.nce

import com.wangxiuwen.coursebox.core.CourseLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Bilingual line pair — one row of the lesson transcript.
 * Plain-text only; HTML stripping happens during decode.
 *
 * `startMs` / `endMs` carry forced-alignment timestamps when the package
 * was produced by `scripts/align_lessons.py`; sentinel value -1 means
 * "no timestamp available" and the renderer must fall back to a
 * position-ratio approximation.
 */
data class NceLine(
    val en: String,
    val cn: String,
    val startMs: Long = -1L,
    val endMs: Long = -1L,
)

data class NceWord(val word: String, val pron: String, val pos: String, val definition: String)

data class NceSection(
    val title: String,
    val type: String,
    val dialogues: List<NceLine>,
    val words: List<NceWord>,
    val text: List<String>,
)

/**
 * One lesson loaded from the course package's lessons.json. Both bundle
 * variants are supported:
 *   - new schema with `audio_hash` / `video_hash` (sha256:...) — preferred
 *   - legacy schema with `audio_local` ("nce-mp3/<book>/<file>.mp3") — falls
 *     back to logical-path resolution against the imported package
 *
 * `audioHash` and `videoHash` are mutually exclusive per lesson: the
 * packager writes the sha to the one matching the file's MIME family.
 * The Vm uses `videoHash.isNotBlank()` to flip `hasVideo` immediately on
 * lesson change (no need to wait for ExoPlayer's onVideoSizeChanged) and
 * to pick the right MediaItem URI.
 */
data class NceLesson(
    val id: String,
    val book: Int,
    val lesson: Int,
    val titleEn: String,
    val titleCn: String,
    val question: String,
    val audioHash: String,
    val videoHash: String,
    val audioLocal: String,
    val audioRemote: String,
    val articleHtml: String,
    val lines: List<NceLine>,
    val sections: List<NceSection>,
) {
    val numberLabel: String get() = if (lesson > 0) "第${lesson}课" else id
    val bookLabel: String get() = when (book) {
        900 -> "英语900"
        in 1..4 -> "第${book}册"
        else -> "其他"
    }

    val isVideo: Boolean get() = videoHash.isNotBlank()

    /** Resolve the playable URI for this lesson. Video lessons prefer the
     *  videoHash object; audio lessons fall back through audioHash →
     *  audioLocal → audioRemote (kept for legacy NCE packages). */
    fun resolveMediaPath(library: CourseLibrary): String? {
        if (videoHash.isNotBlank()) {
            library.resolve(hash = videoHash)?.let { return it }
        }
        return library.resolve(
            hash = audioHash.ifBlank { null },
            logicalPath = audioLocal.ifBlank { null },
        ) ?: audioRemote.ifBlank { null }
    }

    @Deprecated("Use resolveMediaPath", ReplaceWith("resolveMediaPath(library)"))
    fun resolveAudioPath(library: CourseLibrary): String? = resolveMediaPath(library)
}

private val JSON = Json { ignoreUnknownKeys = true }

suspend fun loadNceLessons(library: CourseLibrary, courseId: String): List<NceLesson> =
    withContext(Dispatchers.IO) {
        val pkg = library.packageById(courseId) ?: return@withContext emptyList()
        val file = java.io.File(pkg.lessonsManifestPath)
        if (!file.exists()) return@withContext emptyList()
        val arr = runCatching { JSON.parseToJsonElement(file.readText()) }.getOrNull() as? JsonArray
            ?: return@withContext emptyList()
        arr.mapNotNull { decodeLesson(it as? JsonObject ?: return@mapNotNull null) }
            .sortedWith(compareBy({ it.book }, { it.lesson }))
    }

private fun decodeLesson(obj: JsonObject): NceLesson? {
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun intOf(key: String) = (obj[key] as? JsonPrimitive)?.intOrNull ?: 0

    val id = str("id").ifBlank { return null }
    val sections = decodeSections(obj["sections"])
    val lines = decodeLines(obj["lines"]).ifEmpty { buildLinesFromSections(sections) }
    return NceLesson(
        id = id,
        book = intOf("book"),
        lesson = intOf("lesson"),
        titleEn = str("title_en"),
        titleCn = str("title_cn"),
        question = cleanQuestion(str("question")),
        audioHash = str("audio_hash"),
        videoHash = str("video_hash"),
        audioLocal = str("audio_local"),
        audioRemote = str("audio_url"),
        articleHtml = str("article_html"),
        lines = lines,
        sections = sections,
    )
}

// Strip the publisher boilerplate ("新概念英语－听录音，并回答问题") from
// the question text. Real question content (when present) is appended after
// a newline; without this strip lessons that have no real question render
// as "tab visible but content looks empty / generic".
private val BOILERPLATE_QUESTION = Regex("新概念英语[\\s\\-－—]*听录音[，,]*并回答问题")

private fun cleanQuestion(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.replace(BOILERPLATE_QUESTION, "").trim()
}

// Direct {en, cn} legacy schema — older packages and any future
// schema versions that ship pre-paired bilingual transcripts.
//
// `start_ms` / `end_ms` are emitted by `scripts/align_lessons.py` after
// forced alignment; absent in legacy packages, so default to -1.
private fun decodeLines(node: JsonElement?): List<NceLine> {
    val arr = node as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val en = (o["en"] as? JsonPrimitive)?.contentOrNull
            ?: (o["english"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        val cn = (o["cn"] as? JsonPrimitive)?.contentOrNull
            ?: (o["chinese"] as? JsonPrimitive)?.contentOrNull
            ?: ""
        if (en.isBlank() && cn.isBlank()) return@mapNotNull null
        val startMs = (o["start_ms"] as? JsonPrimitive)?.longOrNull ?: -1L
        val endMs = (o["end_ms"] as? JsonPrimitive)?.longOrNull ?: -1L
        NceLine(en.trim(), cn.trim(), startMs, endMs)
    }
}

// Live NCE packages put English in section titled "...课文" and the
// matching Chinese in "...翻译". 英语900句 instead inlines both languages
// on the same line of a "句子" section, separated by "—" (em-dash), e.g.
//   "1. Hello! / Hi! — 你好！"
// Both shapes get folded into the [NceLine] list so downstream
// auto-scroll / highlight logic doesn't care which course the lesson
// came from.
private val EN_CN_SEPARATOR = Regex("\\s*[—–]\\s*")
private val LEADING_NUMBER = Regex("^\\s*\\d+[.、)]\\s*")

private fun buildLinesFromSections(sections: List<NceSection>): List<NceLine> {
    // Preferred shape: parallel "课文" + "翻译" sections.
    val en = sections.firstOrNull { it.type == "text" && it.title.contains("课文") }?.text.orEmpty()
    val cn = sections.firstOrNull { it.type == "text" && it.title.contains("翻译") }?.text.orEmpty()
    if (en.isNotEmpty() || cn.isNotEmpty()) {
        val n = maxOf(en.size, cn.size)
        return (0 until n).map { i ->
            NceLine(
                en = en.getOrNull(i).orEmpty().trim(),
                cn = cn.getOrNull(i).orEmpty().trim(),
            )
        }
    }
    // Fallback: a single text section (e.g. "句子" for 英语900) where
    // each line is "English — 中文". Strip a leading "1. " ordinal and
    // split on the em-dash.
    val merged = sections.firstOrNull { it.type == "text" && it.text.isNotEmpty() }?.text.orEmpty()
    if (merged.isEmpty()) return emptyList()
    return merged.map { raw ->
        val stripped = raw.replace(LEADING_NUMBER, "").trim()
        val parts = stripped.split(EN_CN_SEPARATOR, limit = 2)
        when (parts.size) {
            2 -> NceLine(en = parts[0].trim(), cn = parts[1].trim())
            else -> NceLine(en = stripped, cn = "")
        }
    }
}

private fun decodeSections(node: JsonElement?): List<NceSection> {
    val arr = node as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        NceSection(
            title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            type = (o["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            dialogues = decodeLines(o["dialogue"]),
            words = decodeWords(o["words"]),
            text = (o["text"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            } ?: emptyList(),
        )
    }
}

private fun decodeWords(node: JsonElement?): List<NceWord> {
    val arr = node as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        NceWord(
            word = (o["word"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            pron = (o["pron"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            pos = (o["pos"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            definition = (o["def"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
}
