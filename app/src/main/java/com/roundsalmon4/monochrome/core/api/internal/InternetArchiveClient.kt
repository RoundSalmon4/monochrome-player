package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roundsalmon4.monochrome.core.util.StringUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-signup fallback backed by the Internet Archive (archive.org).
 * Serves lossless FLAC when an item has it, otherwise VBR MP3 / Ogg Vorbis,
 * all streamed directly over HTTP with no authentication.
 */
@Singleton
class InternetArchiveClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-InternetArchive"
        private const val SEARCH_URL = "https://archive.org/advancedsearch.php"
        private const val METADATA_URL = "https://archive.org/metadata/"
        private const val DOWNLOAD_URL = "https://archive.org/download/"
        private const val MAX_ITEMS = 4
        private const val MAX_FILES = 40
    }

    private val gson = Gson()

    @Volatile
    var wasNotFound: Boolean = false

    suspend fun getStreamUrl(
        title: String,
        artist: String
    ): MonochromeStreamResult? {
        wasNotFound = false
        if (title.isBlank()) return null

        val identifiers = searchIdentifiers(title, artist)
        if (identifiers.isEmpty()) {
            Log.d(TAG, "Internet Archive: no items for '$title' - $artist")
            wasNotFound = true
            return null
        }

        for (id in identifiers) {
            val file = findAudioFile(id, title, artist)
            if (file != null) {
                val url = downloadUrl(id, file.name)
                Log.i(TAG, "Internet Archive: matched ${file.mime} in item $id")
                return MonochromeStreamResult(url = url, mimeType = file.mime, title = file.title)
            }
        }

        Log.d(TAG, "Internet Archive: no matching audio file for '$title' - $artist in ${identifiers.size} items")
        wasNotFound = true
        return null
    }

    private suspend fun searchIdentifiers(title: String, artist: String): List<String> {
        val queries = buildList {
            if (artist.isNotBlank()) add("title:\"$title\" AND creator:\"$artist\" AND mediatype:audio")
            add("title:\"$title\" AND mediatype:audio")
        }
        val found = linkedSetOf<String>()
        for (query in queries) {
            val url = buildString {
                append(SEARCH_URL).append("?q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                append("&fl%5B%5D=identifier&fl%5B%5D=creator&rows=20&output=json")
            }
            try {
                val body = fetch(url) ?: continue
                val docs = runCatching {
                    val root = gson.fromJson<Map<String, Any?>>(body, object : TypeToken<Map<String, Any?>>() {}.type)
                    ((root["response"] as? Map<*, *>)?.get("docs") as? List<Map<String, Any?>>).orEmpty()
                }.getOrElse {
                    Log.w(TAG, "Internet Archive: bad search response: ${it.message}")
                    emptyList()
                }
                for (doc in docs) {
                    val id = doc["identifier"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    if (found.add(id) && found.size >= MAX_ITEMS) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Internet Archive: search failed: ${e.message}")
            }
        }
        return found.toList()
    }

    private suspend fun findAudioFile(identifier: String, title: String, artist: String): AudioFile? {
        val url = METADATA_URL + identifier
        val body = fetch(url) ?: return null
        val root = runCatching {
            gson.fromJson<Map<String, Any?>>(body, object : TypeToken<Map<String, Any?>>() {}.type)
        }.getOrNull() ?: return null

        val files = root["files"] as? List<Map<String, Any?>> ?: return null

        val itemArtist = (root["metadata"] as? Map<*, *>)?.get("creator")?.toString()?.takeIf { it.isNotBlank() }

        var best: AudioFile? = null
        for (file in files.take(MAX_FILES)) {
            val name = file["name"]?.toString() ?: continue
            val mime = mimeForName(name)
            val fileTitle = file["title"]?.toString()?.takeIf { it.isNotBlank() }
                ?: nameWithoutExtension(name)
            if (mime == null) continue
            if (!StringUtil.titlesMatch(title, fileTitle)) continue

            val fileArtist = file["artist"]?.toString()?.takeIf { it.isNotBlank() }
            if (artist.isNotBlank()) {
                val effectiveArtist = fileArtist ?: itemArtist
                if (!effectiveArtist.isNullOrBlank() && !containsWord(effectiveArtist, artist)) continue
            }

            val quality = qualityRank(mime)
            if (best == null || quality > best.quality) {
                best = AudioFile(name = name, mime = mime, title = fileTitle, quality = quality)
            }
        }
        return best?.takeIf { it.quality > 0 }
    }

    private fun qualityRank(mime: String): Int = when (mime) {
        "audio/flac" -> 4
        "audio/wav" -> 3
        "audio/ogg" -> 2
        "audio/mpeg" -> 1
        else -> 0
    }

    private fun mimeForName(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") || lower.endsWith(".wave") -> "audio/wav"
            lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            else -> null
        }
    }

    private fun nameWithoutExtension(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').trim()

    private fun containsWord(container: String, wanted: String): Boolean {
        val wantedTokens = wanted.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.length > 2 }
        if (wantedTokens.isEmpty()) return true
        val containerTokens = container.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }.toSet()
        return wantedTokens.any { containerTokens.contains(it) }
    }

    private fun downloadUrl(identifier: String, name: String): String {
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("%2F", "/")
        return DOWNLOAD_URL + identifier + "/" + encoded
    }

    private suspend fun fetch(url: String): String? {
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "Internet Archive: HTTP ${it.code} for $url")
                    null
                } else it.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Internet Archive: request failed for $url: ${e.message}")
            null
        }
    }

    private data class AudioFile(
        val name: String,
        val mime: String,
        val title: String,
        val quality: Int
    )
}