package com.roundsalmon4.monochrome.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roundsalmon4.monochrome.core.api.Availability

/**
 * Small colored dot representing an album's backend availability:
 * green = all tracks available, amber = some, red = none, grey = not checked yet.
 */
@Composable
fun AlbumStatusDot(status: Availability, modifier: Modifier = Modifier) {
    val color = when (status) {
        Availability.ALL_AVAILABLE -> Color(0xFF2E7D32)
        Availability.SOME_AVAILABLE -> Color(0xFFF9A825)
        Availability.NONE_AVAILABLE -> MaterialTheme.colorScheme.error
        Availability.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}