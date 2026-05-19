package com.wangxiuwen.coursebox.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedurally-drawn abstract cover art, picked deterministically from a
 * course id. Each "pattern" sits on top of the [CourseTone] gradient so
 * the card still telegraphs its tone family while reading as a unique
 * cover at a glance.
 *
 * All art is drawn at runtime with Compose Canvas — zero APK weight, no
 * third-party licensing concerns. If we want real photography later,
 * swap [CoverArt] for an Image composable pointing at res/drawable.
 *
 * Patterns:
 *   0 horizontal waves
 *   1 concentric arcs (corner)
 *   2 dot grid
 *   3 diagonal stripes
 *   4 stacked mountains
 *   5 triangle weave
 */
@Composable
fun CoverArt(id: String, tone: CourseTone, modifier: Modifier = Modifier) {
    val patternIdx = Math.floorMod(id.hashCode(), 6)
    Canvas(modifier = modifier.fillMaxSize()) {
        // Base highlights — a soft top-left light to add depth on top of
        // the underlying gradient that the parent already drew.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.12f),
                radius = size.minDimension * 0.9f,
            ),
            size = size,
        )
        when (patternIdx) {
            0 -> waves(tone)
            1 -> arcs(tone)
            2 -> dots(tone)
            3 -> stripes(tone)
            4 -> mountains(tone)
            else -> weave(tone)
        }
        // Top + bottom scrims so the 课次 badge (top-left) and the tone
        // label / title (bottom) stay readable regardless of which
        // pattern stroke happens to cross them. ~28% black at the very
        // edges, fading to transparent in the middle third of the card.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.30f,
            ),
            size = Size(size.width, size.height * 0.30f),
            topLeft = Offset(0f, 0f),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.40f)),
                startY = 0f,
                endY = size.height * 0.40f,
            ),
            size = Size(size.width, size.height * 0.40f),
            topLeft = Offset(0f, size.height * 0.60f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.waves(tone: CourseTone) {
    val ink = Color.White.copy(alpha = 0.18f)
    val accent = tone.gradStart.copy(alpha = 0.55f)
    val amp = size.height * 0.06f
    val baseY = size.height * 0.55f
    for (i in 0 until 5) {
        val yOff = baseY + i * size.height * 0.08f
        val path = Path().apply {
            moveTo(-10f, yOff)
            var x = 0f
            while (x < size.width + 10f) {
                val y = yOff + sin((x / size.width) * 4 * PI + i).toFloat() * amp
                lineTo(x, y)
                x += 4f
            }
        }
        drawPath(path, color = if (i % 2 == 0) ink else accent, style = Stroke(width = 2.5f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.arcs(tone: CourseTone) {
    val ink = Color.White.copy(alpha = 0.22f)
    val accent = tone.gradStart.copy(alpha = 0.45f)
    val maxR = min(size.width, size.height) * 1.4f
    for (i in 0 until 8) {
        val r = maxR * (0.25f + i * 0.12f)
        drawCircle(
            color = if (i % 2 == 0) ink else accent,
            radius = r,
            center = Offset(size.width * 0.9f, size.height * 1.05f),
            style = Stroke(width = 2f),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dots(tone: CourseTone) {
    val ink = Color.White.copy(alpha = 0.32f)
    val step = size.minDimension * 0.085f
    val r = step * 0.18f
    var y = step
    while (y < size.height) {
        var x = step + (if ((y / step).toInt() % 2 == 0) 0f else step / 2f)
        while (x < size.width) {
            drawCircle(color = ink, radius = r, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.stripes(tone: CourseTone) {
    val ink = Color.White.copy(alpha = 0.14f)
    val accent = tone.gradStart.copy(alpha = 0.45f)
    val w = size.minDimension * 0.18f
    val effect = PathEffect.cornerPathEffect(8f)
    var x = -size.height
    var i = 0
    while (x < size.width) {
        val path = Path().apply {
            moveTo(x, size.height + 10f)
            lineTo(x + size.height, -10f)
            lineTo(x + size.height + w, -10f)
            lineTo(x + w, size.height + 10f)
            close()
        }
        drawPath(path, color = if (i % 2 == 0) ink else accent, style = Stroke(width = 1f, pathEffect = effect))
        x += w * 1.8f
        i += 1
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.mountains(tone: CourseTone) {
    val backColor = Color.White.copy(alpha = 0.10f)
    val midColor = tone.gradStart.copy(alpha = 0.35f)
    val frontColor = Color.Black.copy(alpha = 0.25f)

    fun ridge(yBase: Float, jitter: Float, color: Color) {
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, yBase)
            var x = 0f
            var up = true
            while (x < size.width) {
                val step = size.width * 0.18f
                val targetY = yBase + (if (up) -jitter else jitter / 2f)
                lineTo(x + step, targetY)
                x += step
                up = !up
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color = color)
    }
    ridge(size.height * 0.55f, size.height * 0.18f, backColor)
    ridge(size.height * 0.7f, size.height * 0.12f, midColor)
    ridge(size.height * 0.82f, size.height * 0.08f, frontColor)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.weave(tone: CourseTone) {
    val ink = Color.White.copy(alpha = 0.20f)
    val accent = tone.gradStart.copy(alpha = 0.5f)
    val cell = size.minDimension * 0.16f
    var y = 0f
    var row = 0
    while (y < size.height + cell) {
        var x = -cell
        var col = 0
        while (x < size.width + cell) {
            val flip = (row + col) % 2 == 0
            val color = if (flip) ink else accent
            val path = Path().apply {
                if (flip) {
                    moveTo(x, y)
                    lineTo(x + cell, y)
                    lineTo(x, y + cell)
                } else {
                    moveTo(x + cell, y)
                    lineTo(x + cell, y + cell)
                    lineTo(x, y + cell)
                }
                close()
            }
            drawPath(path, color = color)
            x += cell
            col += 1
        }
        y += cell
        row += 1
    }
    // A subtle accent diagonal across the whole image so neighbouring
    // cards with the same pattern still look distinct.
    val angle = (Math.floorMod(tone.label.hashCode(), 360)).toFloat() * PI.toFloat() / 180f
    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = Offset(size.width / 2f - cos(angle) * size.minDimension, size.height / 2f - sin(angle) * size.minDimension),
        end = Offset(size.width / 2f + cos(angle) * size.minDimension, size.height / 2f + sin(angle) * size.minDimension),
        strokeWidth = 2f,
    )
}
