package com.roundsalmon4.monochrome.ui.player

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaveformDecoder @Inject constructor() {

    companion object {
        private const val TAG = "ChromePlayer-Waveform"
        private const val TARGET_SAMPLES = 300
        private const val CACHE_SIZE = 10
    }

    private val cache = object : LruCache<String, FloatArray>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: FloatArray) = 1
    }

    suspend fun decode(url: String): FloatArray? {
        cache.get(url)?.let { return it }

        return try {
            val result = withContext(Dispatchers.IO) { decodeFromUrl(url) }
            if (result != null) {
                cache.put(url, result)
                Log.d(TAG, "Decoded ${result.size} samples for $url")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Waveform decode failed: ${e.message}")
            null
        }
    }

    private fun decodeFromUrl(url: String): FloatArray? {
        val tempFile = File.createTempFile("waveform", ".tmp")
        try {
            val conn = java.net.URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 ChromePlayer/0.1")
            }
            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            val ext = tempFile.extension.ifBlank {
                val contentType = conn.contentType ?: ""
                when {
                    contentType.contains("flac") -> "flac"
                    contentType.contains("mp4") || contentType.contains("m4a") -> "m4a"
                    contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
                    else -> "tmp"
                }
            }

            return decodeToPeaks(tempFile, ext)
        } catch (e: Exception) {
            Log.w(TAG, "decodeFromUrl failed: ${e.message}")
            return null
        } finally {
            tempFile.delete()
        }
    }

    private fun decodeToPeaks(file: File, extension: String): FloatArray? {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null

            val c = android.media.MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            // Downsample peaks per-chunk to avoid accumulating full PCM in memory.
            // Target ~TARGET_SAMPLES total; estimate bins per chunk based on duration ratio.
            val totalDurationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
            val peakAccum = FloatArray(TARGET_SAMPLES)
            val peakCounts = IntArray(TARGET_SAMPLES)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalSamplesDecoded = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = c.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = c.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            c.queueInputBuffer(inputIndex, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = c.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunkSize = bufferInfo.size / 2
                        val shortArray = ShortArray(chunkSize)
                        outputBuffer.asShortBuffer().get(shortArray)

                        // Downsample this chunk's peaks directly into target bins
                        val chunkStartSample = totalSamplesDecoded
                        val samplesPerBin = maxOf(1.0, chunkSize.toDouble() / TARGET_SAMPLES)
                        for (i in 0 until chunkSize) {
                            val bin = ((chunkStartSample + i) * TARGET_SAMPLES / maxOf(1L, totalDurationUs / 1000 * 44100 / 2)).toInt()
                                .coerceIn(0, TARGET_SAMPLES - 1)
                            val abs = kotlin.math.abs(shortArray[i].toInt())
                            if (abs > peakAccum[bin].toInt()) {
                                peakAccum[bin] = abs.toFloat()
                            }
                        }
                        totalSamplesDecoded += chunkSize
                    }
                    c.releaseOutputBuffer(outputIndex, false)
                }
            }

            // Normalize to 0..1
            if (totalSamplesDecoded == 0L) return null
            val maxPeak = peakAccum.maxOrNull() ?: 1f
            if (maxPeak <= 0f) return FloatArray(TARGET_SAMPLES) { 0.05f }
            return FloatArray(TARGET_SAMPLES) { (peakAccum[it] / maxPeak).coerceIn(0.05f, 1f) }
        } catch (e: Exception) {
            Log.w(TAG, "decodeToPeaks failed: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
