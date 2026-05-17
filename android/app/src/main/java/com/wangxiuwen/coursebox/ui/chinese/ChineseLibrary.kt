package com.wangxiuwen.coursebox.ui.chinese

import com.wangxiuwen.coursebox.core.CourseLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ChineseFormat { TangPoem, SongCi, Shijing, ChapterParagraphs }

data class ChineseSource(
    val key: String,
    val label: String,
    val logicalPath: String,
    val dynasty: String,
    val defaultAuthor: String,
    val typeLabel: String,
    val format: ChineseFormat,
    val summary: String = "",
)

data class ChineseWork(
    val id: String,
    val title: String,
    val author: String,
    val dynasty: String,
    val sourceKey: String,
    val sourceLabel: String,
    val typeLabel: String,
    val paragraphs: List<String>,
    val tags: List<String> = emptyList(),
    val rhythmic: String? = null,
    val chapter: String? = null,
    val section: String? = null,
) {
    val displayAuthor: String get() = author.ifBlank { "佚名" }

    val preview: String
        get() {
            if (paragraphs.isEmpty()) return ""
            val snippet = paragraphs.take(2).joinToString(" ")
            return if (snippet.length > 48) snippet.substring(0, 48) + "…" else snippet
        }

    val subtitle: String
        get() = buildList {
            if (dynasty.isNotBlank()) add(dynasty)
            add(displayAuthor)
            if (!rhythmic.isNullOrBlank() && rhythmic.trim() != title.trim()) add(rhythmic.trim())
            add(sourceLabel)
        }.joinToString(" · ")

    val searchableText: String
        get() = buildList {
            add(title)
            add(displayAuthor)
            add(dynasty)
            rhythmic?.let { add(it) }
            chapter?.let { add(it) }
            section?.let { add(it) }
            addAll(tags)
            addAll(paragraphs)
            add(sourceLabel)
            add(typeLabel)
        }.joinToString(" ").lowercase()
}

val chineseSources: List<ChineseSource> = listOf(
    ChineseSource("tangshi300", "唐诗三百首", "poetry/tangshi300.json", "唐", "佚名", "诗", ChineseFormat.TangPoem, "唐诗代表作选集"),
    ChineseSource("songci300", "宋词三百首", "poetry/songci300.json", "宋", "佚名", "词", ChineseFormat.SongCi, "宋词名篇，附词牌"),
    ChineseSource("shijing", "诗经", "poetry/shijing.json", "先秦", "佚名", "经", ChineseFormat.Shijing, "国风、雅、颂全卷"),
    ChineseSource("lunyu", "论语", "poetry/lunyu.json", "先秦", "孔子及弟子", "经", ChineseFormat.ChapterParagraphs),
    ChineseSource("mengzi", "孟子", "poetry/mengzi.json", "战国", "孟子", "经", ChineseFormat.ChapterParagraphs),
    ChineseSource("daxue", "大学", "poetry/daxue.json", "先秦", "传为曾子", "经", ChineseFormat.ChapterParagraphs),
    ChineseSource("zhongyong", "中庸", "poetry/zhongyong.json", "先秦", "传为子思", "经", ChineseFormat.ChapterParagraphs),
)

private val JSON = Json { ignoreUnknownKeys = true }

suspend fun loadChineseLibrary(library: CourseLibrary): List<ChineseWork> = withContext(Dispatchers.IO) {
    val out = mutableListOf<ChineseWork>()
    for (source in chineseSources) {
        val text = library.loadString(source.logicalPath) ?: continue
        out.addAll(decodeSource(source, text))
    }
    out
}

private fun decodeSource(source: ChineseSource, raw: String): List<ChineseWork> {
    val root = runCatching { JSON.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    val items: List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(root)
        else -> emptyList()
    }
    return items.mapIndexedNotNull { idx, obj -> decodeItem(source, obj, idx) }
}

private fun decodeItem(source: ChineseSource, obj: JsonObject, index: Int): ChineseWork? {
    return when (source.format) {
        ChineseFormat.TangPoem -> decodeTang(source, obj, index)
        ChineseFormat.SongCi -> decodeCi(source, obj, index)
        ChineseFormat.Shijing -> decodeShijing(source, obj, index)
        ChineseFormat.ChapterParagraphs -> decodeChapter(source, obj, index)
    }
}

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.list(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun decodeTang(source: ChineseSource, obj: JsonObject, index: Int): ChineseWork {
    val title = obj.str("title").ifBlank { "无题" }
    val author = obj.str("author").ifBlank { source.defaultAuthor }
    val paragraphs = obj.list("paragraphs")
    val tags = obj.list("tags")
    return ChineseWork(
        id = "${source.key}-$index",
        title = title,
        author = author,
        dynasty = source.dynasty,
        sourceKey = source.key,
        sourceLabel = source.label,
        typeLabel = source.typeLabel,
        paragraphs = paragraphs,
        tags = tags,
    )
}

private fun decodeCi(source: ChineseSource, obj: JsonObject, index: Int): ChineseWork {
    val rhythmic = obj.str("rhythmic")
    val title = obj.str("title").ifBlank { rhythmic.ifBlank { "无题" } }
    val author = obj.str("author").ifBlank { source.defaultAuthor }
    return ChineseWork(
        id = "${source.key}-$index",
        title = title,
        author = author,
        dynasty = source.dynasty,
        sourceKey = source.key,
        sourceLabel = source.label,
        typeLabel = source.typeLabel,
        paragraphs = obj.list("paragraphs"),
        tags = obj.list("tags"),
        rhythmic = rhythmic.ifBlank { null },
    )
}

private fun decodeShijing(source: ChineseSource, obj: JsonObject, index: Int): ChineseWork {
    val title = obj.str("title").ifBlank { "无题" }
    val chapter = obj.str("chapter")
    val section = obj.str("section")
    return ChineseWork(
        id = "${source.key}-$index",
        title = title,
        author = source.defaultAuthor,
        dynasty = source.dynasty,
        sourceKey = source.key,
        sourceLabel = source.label,
        typeLabel = source.typeLabel,
        paragraphs = obj.list("content").ifEmpty { obj.list("paragraphs") },
        chapter = chapter.ifBlank { null },
        section = section.ifBlank { null },
    )
}

private fun decodeChapter(source: ChineseSource, obj: JsonObject, index: Int): ChineseWork {
    val chapter = obj.str("chapter").ifBlank { "第${index + 1}章" }
    val paragraphs = obj.list("paragraphs").ifEmpty { obj.list("content") }
    return ChineseWork(
        id = "${source.key}-$index",
        title = chapter,
        author = source.defaultAuthor,
        dynasty = source.dynasty,
        sourceKey = source.key,
        sourceLabel = source.label,
        typeLabel = source.typeLabel,
        paragraphs = paragraphs,
        chapter = chapter,
    )
}
