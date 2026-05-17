package com.wangxiuwen.coursebox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 3D Y-axis flip between two faces. Front shows when [showBack] is false,
 * back shows when true. The back face is pre-rotated 180° so its content
 * isn't mirrored after the flip.
 *
 * Both faces sit in the same [Box], so the parent should constrain the
 * frame (height / aspect ratio) — otherwise the back face's intrinsic
 * size would change the layout.
 */
@Composable
fun FlipCard(
    showBack: Boolean,
    modifier: Modifier = Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (showBack) 180f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "flip-card",
    )
    Box(
        modifier = modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 12f * density
        },
    ) {
        if (rotation <= 90f) {
            front()
        } else {
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                back()
            }
        }
    }
}
