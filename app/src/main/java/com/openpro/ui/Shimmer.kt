package com.openpro.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

private const val SHIMMER_MS = 1200

/**
 * A light band sweeping across a placeholder. The band's position comes from the clock rather
 * than each placeholder's own animation, so every placeholder on screen sweeps in step.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = lerp(base, MaterialTheme.colorScheme.onSurfaceVariant, 0.14f)
    // Only read while drawing, to redraw every frame without recomposing.
    val tick by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "shimmer")
    return drawBehind {
        tick
        val phase = (SystemClock.uptimeMillis() % SHIMMER_MS) / SHIMMER_MS.toFloat()
        val band = maxOf(size.width, size.height)
        val x = -band + (size.width + 2 * band) * phase
        drawRect(base)
        drawRect(Brush.linearGradient(listOf(base, highlight, base), start = Offset(x, 0f), end = Offset(x + band, size.height)))
    }
}

/** A shimmering bar the height of one line of [style], [fraction] of the available width. */
@Composable
fun ShimmerLine(style: TextStyle, fraction: Float) {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        Modifier.padding(vertical = line * 0.15f).fillMaxWidth(fraction).height(line * 0.7f)
            .clip(RoundedCornerShape(4.dp)).shimmer(),
    )
}
