package com.wangxiuwen.coursebox.packager

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wangxiuwen.coursebox.packager.ui.PackagerApp

fun main() = application {
    val state = rememberWindowState(size = DpSize(980.dp, 720.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "课程盒子 · 打包工具",
        state = state,
    ) {
        MaterialTheme(
            colorScheme = if (isSystemDark()) darkColorScheme() else lightColorScheme(),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                PackagerApp()
            }
        }
    }
}

/** Crude system-theme probe — good enough for a dev tool. */
private fun isSystemDark(): Boolean {
    // JVM has no portable API; fall back to false. The user can theme via OS.
    return false
}
