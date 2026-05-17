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

@Composable
fun toneFor(type: String): CourseTone = when (type) {
    "nce" -> CourseTone(
        label = "新概念 · 听 · 读",
        short = "NCE",
        icon = Icons.Default.AutoStories,
        gradStart = Color(0xFF38BDF8),
        gradMid = Color(0xFF1E40AF),
        gradEnd = Color(0xFF0C1E54),
    )
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
    else -> CourseTone(
        label = "通用音频课程",
        short = "♬",
        icon = Icons.Default.AutoStories,
        gradStart = AppColors.Accent,
        gradMid = AppColors.AccentDark,
        gradEnd = Color(0xFF064E3B),
    )
}

/** iOS-system blue accent — primary highlight across tabs, pills, icons. */
val AccentBlue = Color(0xFF007AFF)
val DarkInk = Color(0xFF0E0D0E)
