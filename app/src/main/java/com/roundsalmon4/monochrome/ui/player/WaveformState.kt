package com.roundsalmon4.monochrome.ui.player

data class WaveformState(
    val samples: FloatArray = floatArrayOf(),
    val isLoaded: Boolean = false,
    val isError: Boolean = false
) {
    val sampleCount: Int get() = samples.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformState) return false
        return samples.contentEquals(other.samples) && isLoaded == other.isLoaded && isError == other.isError
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + isLoaded.hashCode()
        result = 31 * result + isError.hashCode()
        return result
    }
}
