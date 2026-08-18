package com.roundsalmon4.monochrome.ui.player

import android.util.Log
import android.util.LruCache
import android.util.Range
import android.util.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        try {
            val tempFile = File.createTempFile("waveform", ".tmp")
            tempFile.deleteOnExit()

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

            val rawPcm = decodeToPcm(tempFile, ext)
            tempFile.delete()

            if (rawPcm == null || rawPcm.isEmpty()) return null
            return downsamplePeaks(rawPcm, TARGET_SAMPLES)
        } catch (e: Exception) {
            Log.w(TAG, "decodeFromUrl failed: ${e.message}")
            return null
        }
    }

    private fun decodeToPcm(file: File, extension: String): ShortArray? {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null

            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmChunks = mutableListOf<ShortArray>()
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortArray = ShortArray(bufferInfo.size / 2)
                        outputBuffer.asShortBuffer().get(shortArray)
                        pcmChunks.add(shortArray)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()

            if (pcmChunks.isEmpty()) return null
            val totalSize = pcmChunks.sumOf { it.size }
            val merged = ShortArray(totalSize)
            var pos = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, merged, pos, chunk.size)
                pos += chunk.size
            }
            return merged
        } catch (e: Exception) {
            Log.w(TAG, "decodeToPcm failed: ${e.message}")
            return null
        } finally {
            extractor.release()
        }
    }

    private fun downsamplePeaks(pcm: ShortArray, targetSamples: Int): FloatArray {
        val samplesPerBin = maxOf(1, pcm.size / targetSamples)
        val result = FloatArray(targetSamples)
        for (i in 0 until targetSamples) {
            val start = i * samplesPerBin
            val end = minOf(start + samplesPerBin, pcm.size)
            if (start >= pcm.size) break
            var maxAbs = 0
            for (j in start until end) {
                val abs = kotlin.math.abs(pcm[j].toInt())
                if (abs > maxAbs) maxAbs = abs
            }
            result[i] = maxAbs.toFloat() / Short.MAX_VALUE.toFloat()
        }
        return result
    }
}
