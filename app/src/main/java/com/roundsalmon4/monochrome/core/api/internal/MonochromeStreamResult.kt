package com.roundsalmon4.monochrome.core.api.internal

/** A playable stream resolved by one of the playback source clients. */
data class MonochromeStreamResult(
    val url: String,
    val mimeType: String
)