package com.wangxiuwen.coursebox.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Visual tokens — colour palettes for the F (小红书) + H (Apple Music)
 * hybrid we're going for. Per course-type gradients are deep enough to
 * read on the dark player screen while still feeling distinct in the
 * white-on-light library grid.
 */
data class CourseTone(
    val label: String,
    val short: String,
    val icon: ImageVector,
    val gradStart: Color,
    val gradMid: Color,
    val gradEnd: Color,
) {
    val gradient: Brush get() = Brush.linearGradient(listOf(gradStart, gradMid, gradEnd))
}

/**
 * Per-id palette so a library full of NCE-typed packs doesn't render as a
 * wall of identical blue cards. Eight pleasant 3-stop gradients picked
 * for legibility on white text + enough hue separation so adjacent cards
 * never look the same.
 */
private val IdPalette: List<Triple<Color, Color, Color>> = listOf(
    // indigo → deep navy
    Triple(Color(0xFF60A5FA), Color(0xFF1E40AF), Color(0xFF0C1E54)),
    // emerald → forest
    Triple(Color(0xFF34D399), Color(0xFF047857), Color(0xFF064E3B)),
    // amber → bronze
    Triple(Color(0xFFFBBF24), Color(0xFFB45309), Color(0xFF451A03)),
    // rose → wine
    Triple(Color(0xFFFB7185), Color(0xFFBE123C), Color(0xFF4C0519)),
    // violet → plum
    Triple(Color(0xFFA78BFA), Color(0xFF6D28D9), Color(0xFF2E1065)),
    // teal → deep teal
    Triple(Color(0xFF2DD4BF), Color(0xFF0F766E), Color(0xFF134E4A)),
    // peach → terracotta
    Triple(Color(0xFFFB923C), Color(0xFFC2410C), Color(0xFF431407)),
    // slate-cool → midnight
    Triple(Color(0xFF94A3B8), Color(0xFF334155), Color(0xFF0F172A)),
)

private fun paletteFor(id: String): Triple<Color, Color, Color> {
    // Stable across runs: same id → same colour. Math.floorMod handles
    // negative hashCodes (Kotlin's % returns negative for negative dividends).
    val idx = Math.floorMod(id.hashCode(), IdPalette.size)
    return IdPalette[idx]
}

@Composable
fun toneFor(type: String, id: String = ""): CourseTone {
    // Special types keep their themed gradient so the user can read the
    // course's character from the cover. Plain NCE / audio_course / video
    // packs fan out across the palette by id so adjacent cards differ.
    return when (type) {
        "chinese_poetry" -> CourseTone(
            label = "中文经典 · 诗文",
            short = "诗",
            icon = Icons.Default.MenuBook,
            gradStart = Color(0xFFFBBF24),
            gradMid = Color(0xFFB45309),
            gradEnd = Color(0xFF451A03),
        )
        "music" -> CourseTone(
            label = "音乐基础",
            short = "♪",
            icon = Icons.Default.LibraryMusic,
            gradStart = Color(0xFFEC4899),
            gradMid = Color(0xFFBE185D),
            gradEnd = Color(0xFF500724),
        )
        else -> {
            val (s, m, e) = paletteFor(id.ifBlank { type })
            CourseTone(
                label = when (type) {
                    "nce" -> "新概念 · 听 · 读"
                    "video" -> "视频课程"
                    "mixed" -> "混合课程"
                    else -> "通用音频课程"
                },
                short = when (type) {
                    "nce" -> "NCE"
                    "video" -> "▶"
                    else -> "♬"
                },
                icon = Icons.Default.AutoStories,
                gradStart = s,
                gradMid = m,
                gradEnd = e,
            )
        }
    }
}

/** iOS-system blue accent — primary highlight across tabs, pills, icons. */
val AccentBlue = Color(0xFF007AFF)
val DarkInk = Color(0xFF0E0D0E)
