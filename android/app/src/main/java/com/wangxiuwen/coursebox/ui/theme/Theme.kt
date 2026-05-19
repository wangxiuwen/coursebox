package com.wangxiuwen.coursebox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object AppColors {
    val Accent = Color(0xFF14B8A6)         // teal-500ish — primary brand
    val AccentDark = Color(0xFF0F766E)
    val BgLight = Color(0xFFF7F9F9)
    val BgDark = Color(0xFF101213)
    val Surface1Light = Color(0xFFFFFFFF)
    val Surface1Dark = Color(0xFF1A1D1F)
    val Surface2Light = Color(0xFFEEF1F2)
    val Surface2Dark = Color(0xFF22272A)
    val Surface3Light = Color(0xFFE2E7E9)
    val Surface3Dark = Color(0xFF2D3338)

    val OnBgStrong = Color(0xFF0E1416)
    val OnBgMid = Color(0xFF40484C)
    val OnBgWeak = Color(0xFF6A7479)
    val OnBgFaint = Color(0xFFAAB4B9)

    val Error = Color(0xFFEA4444)
}

private val LightScheme = lightColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = AppColors.AccentDark,
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color.White,
    background = AppColors.BgLight,
    onBackground = AppColors.OnBgStrong,
    surface = AppColors.Surface1Light,
    onSurface = AppColors.OnBgStrong,
    surfaceVariant = AppColors.Surface2Light,
    onSurfaceVariant = AppColors.OnBgMid,
    error = AppColors.Error,
)

private val DarkScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color.Black,
    background = AppColors.BgDark,
    onBackground = Color(0xFFE7EDEF),
    surface = AppColors.Surface1Dark,
    onSurface = Color(0xFFE7EDEF),
    surfaceVariant = AppColors.Surface2Dark,
    onSurfaceVariant = AppColors.OnBgFaint,
    error = AppColors.Error,
)

/**
 * Force light scheme everywhere. The Library / NCE list / NCE player UIs
 * use hard-coded light surfaces (PaperBg #F5F4F1, Color.White cards), so
 * letting the theme follow the system into dark mode means the text
 * colour pivots to "#E7EDEF on white" — visually invisible.
 *
 * Until we ship proper dark-mode chrome, pin to LightScheme regardless
 * of the system setting. [useDarkTheme] is kept as a parameter so a
 * specific call site can opt back in once it has dark-aware surfaces.
 */
@Composable
fun ParrotTheme(
    useDarkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        useDarkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
