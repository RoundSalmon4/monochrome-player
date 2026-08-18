package com.roundsalmon4.monochrome.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.floor

@Composable
fun WaveformSeekbar(
    waveformSamples: FloatArray?,
    currentPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f)

    val playedColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.surfaceVariant
    val barWidthDp = 2.dp
    val barGapDp = 1.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val newFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek((newFraction * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * durationMs).toLong()) }
                        dragFraction = null
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        val current = dragFraction ?: (if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f)
                        dragFraction = (current + dragAmount / size.width).coerceIn(0f, 1f)
                    }
                )
            }
    ) {
        if (waveformSamples == null || waveformSamples.isEmpty()) {
            // Fallback: simple centered line
            drawRoundRect(
                color = unplayedColor,
                topLeft = Offset(0f, size.height / 2 - 2.dp.toPx()),
                size = Size(size.width, 4.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            return@Canvas
        }

        val sampleCount = waveformSamples.size
        val totalBarWidthPx = size.width / sampleCount
        val barWidthPx = (barWidthDp.toPx()).coerceAtMost(totalBarWidthPx * 0.7f)
        val maxHeight = size.height * 0.9f
        val minHeight = 3.dp.toPx()

        for (i in 0 until sampleCount) {
            val x = i * totalBarWidthPx
            val amplitude = waveformSamples[i].coerceIn(0.05f, 1f)
            val barHeight = (amplitude * maxHeight).coerceAtLeast(minHeight)
            val centerY = size.height / 2
            val isPlayed = (i.toFloat() / sampleCount) <= fraction

            drawRoundRect(
                color = if (isPlayed) playedColor else unplayedColor,
                topLeft = Offset(x, centerY - barHeight / 2),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }

        // Playhead line
        val playheadX = fraction * size.width
        drawLine(
            color = playedColor,
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}
