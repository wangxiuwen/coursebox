package com.wangxiuwen.coursebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wangxiuwen.coursebox.ui.theme.AppColors

/**
 * Slim media slider — 14dp thumb (Accent fill, 1.5dp white border) on a
 * 3dp track, with optional buffer-ahead overlay. Mirrors the 599player
 * design so the control bar feels native to the same family of apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlimSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    bufferRatio: Float = 0f,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (enabled) AppColors.Accent else AppColors.OnBgFaint)
                    .border(1.5.dp, Color.White, CircleShape),
            )
        },
        track = { state ->
            val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(0.001f)
            val activeFraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            val bufFraction = bufferRatio.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(AppColors.OnBgFaint.copy(alpha = 0.45f)),
            ) {
                if (bufFraction > activeFraction) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufFraction)
                            .fillMaxHeight()
                            .background(AppColors.Accent.copy(alpha = 0.35f)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(activeFraction)
                        .fillMaxHeight()
                        .background(if (enabled) AppColors.Accent else AppColors.OnBgFaint),
                )
            }
        },
    )
}

/** mm:ss formatter for the slider time labels. */
fun fmtTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
