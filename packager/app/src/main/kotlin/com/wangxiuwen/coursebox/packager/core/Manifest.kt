package com.wangxiuwen.coursebox.packager.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Authoring-side data model for a CourseBox manifest. Mirrors the
 * Android renderer's `core.CoursePackage` types but lives in its own
 * file so packager and player can evolve independently.
 */
@Serializable
data class CourseResource(
    val hash: String,
    val path: String,
    val size: Long,
    val type: String = "application/octet-stream",
    val origin: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class LessonIndexEntry(
    val id: String,
    val title: String = "",
    val subtitle: String = "",
    @SerialName("audio_hash") val audioHash: String = "",
    val tags: List<String> = emptyList(),
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class CourseEntry(
    val id: String,
    val title: String,
    val description: String = "",
    val type: String = "audio_course",
    @SerialName("lessons_manifest") val lessonsManifest: String,
    @SerialName("lesson_index") val lessonIndex: List<LessonIndexEntry> = emptyList(),
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class CoursePackageManifest(
    val format: String = "parrot-course-package",
    val version: Int = 1,
    @SerialName("generated_at") val generatedAt: String,
    val generator: String,
    val resources: List<CourseResource>,
    val courses: List<CourseEntry>,
)
