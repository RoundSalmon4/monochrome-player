package com.roundsalmon4.monochrome.core.api.internal

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * JioSaavn free AAC-320 fallback. JioSaavn is a large, mostly free, no-sign-up
 * catalog; 320 kbps URLs are delivered as a small DES-encrypted template in the
 * search response (ECB, key "38346591") that decodes to an `_96` CDN link which
 * is remapped to `_320`. The grammar/decrypt step mirrors the approach used by
 * the Stash client. Conservative matching (title + artist + duration gates)
 * keeps wrong-version and region-namesake matches from being played.
 */
@Singleton
class JioSaavnClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-JioSaavn"
        private const val BASE_URL = "https://www.jiosaavn.com"
        private const val DES_KEY = "38346591"
        private const val HOST_COOLDOWN_MS = 90_000L

        private val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val VERSION_MARKERS = listOf(
            "live", "remix", "acoustic", "instrumental", "karaoke", "cover",
            "mashup", "edit", "session", "extended", "radio", "deluxe", "version"
        )
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val hostDownUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    var wasNotFound: Boolean = false

    private data class Song(
        val id: String,
        val name: String,
        val artists: String,
        val album: String,
        val durationSec: Int?,
        val link320: String?
    )

    suspend fun getStreamUrl(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): MonochromeStreamResult? {
        wasNotFound = false
        if (!hostIsUp()) return null

        val queries = listOf(
            listOfNotNull(artist.takeIf { it.isNotBlank() }, title).joinToString(" "),
            title
        ).filter { it.isNotBlank() }.distinct()

        var anySearchOk = false
        for (query in queries) {
            val songs = search(query) ?: continue
            if (songs.isNotEmpty()) anySearchOk = true
            val match = bestMatch(songs, title, artist, album, durationMs) ?: continue
            val url = match.link320 ?: continue
            if (probe(url)) {
                Log.d(TAG, "Resolved '${match.name}' -> $url")
                return MonochromeStreamResult(url = url, mimeType = "audio/mp4")
            }
            Log.d(TAG, "320 link for '${match.name}' not playable, continuing")
        }
        wasNotFound = anySearchOk
        Log.w(TAG, if (anySearchOk) "No acceptable JioSaavn match" else "JioSaavn unreachable or empty")
        return null
    }

    // ------------------------------------------------------------------ search

    private suspend fun search(query: String): List<Song>? {
        val url = "$BASE_URL/api.php?__call=search.getResults&_format=json&_marker=0&ctx=web6dot0&n=20&p=1&q=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "$BASE_URL/")
            .build()
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code in 500..599) markHostDown("search HTTP ${resp.code}")
                        Log.w(TAG, "Search HTTP ${resp.code}")
                        return@use null
                    }
                    resp.body?.string()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search failed: ${e.message}")
                null
            }
        } ?: return null

        if (body.trimStart().startsWith("<")) { markHostDown("search returned HTML"); return null }

        return runCatching {
            val root = gson.fromJson(body, Map::class.java)
            (root["results"] as? List<*>).orEmpty().mapNotNull { item ->
                val m = item as? Map<String, Any?> ?: return@mapNotNull null
                val id = m["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = decodeEntities(m["song"]?.toString()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!m["320kbps"]?.toString().equals("true", ignoreCase = true)) return@mapNotNull null
                val encrypted = m["encrypted_media_url"]?.toString() ?: return@mapNotNull null
                val link = decrypt320(encrypted) ?: return@mapNotNull null
                Song(
                    id = id,
                    name = name,
                    artists = decodeEntities(m["primary_artists"]?.toString()).orEmpty(),
                    album = decodeEntities(m["album"]?.toString()).orEmpty(),
                    durationSec = parseDuration(m["duration"]?.toString()),
                    link320 = link
                )
            }
        }.getOrElse { e -> Log.w(TAG, "Search parse failed: ${e.message}"); null }
    }

    /** DES/ECB decrypt the JioSaavn media template and remap the `_96` link to `_320`. */
    private fun decrypt320(encrypted: String): String? = try {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(DES_KEY.toByteArray(), "DES"))
        val template = String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), Charsets.UTF_8)
        if (!template.contains("_96")) null else template.replace("_96", "_320")
    } catch (e: Exception) {
        Log.w(TAG, "320 decrypt failed: ${e.message}")
        null
    }

    // ------------------------------------------------------------------ match

    private fun bestMatch(
        songs: List<Song>,
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): Song? {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val wantedAlbum = normalize(album)
        val wantedDurationSec = if (durationMs > 0) (durationMs / 1000L).toInt() else null
        val wantedMarkers = versionMarkers(title)
        val minDurationTolerance = maxOf(8.0, durationMs / 1000.0 * 0.03)

        var best: Song? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (song in songs) {
            val candidateMarkers = versionMarkers(song.name)
            if ((candidateMarkers - wantedMarkers).isNotEmpty()) continue

            val candidateTitle = normalize(stripParens(song.name))
            val titleSim = jaccardCore(wantedTitle, candidateTitle)
            if (titleSim < 0.5) continue

            val candidateArtist = normalize(song.artists)
            val artistSim = artistScore(wantedArtist, candidateArtist)
            if (artistSim < 0.6) continue

            if (wantedDurationSec != null && song.durationSec != null &&
                abs(wantedDurationSec - song.durationSec) > minDurationTolerance
            ) continue

            var score = titleSim + artistSim
            if (wantedAlbum.isNotBlank() && song.album.isNotBlank()) {
                score += jaccardCore(wantedAlbum, normalize(song.album)) * 0.3
            }
            if (wantedDurationSec != null && song.durationSec != null) {
                score += 0.2
            }
            if (score > bestScore) {
                best = song
                bestScore = score
            }
        }
        return best
    }

    private fun versionMarkers(text: String): Set<String> {
        val normalized = normalize(text)
        return VERSION_MARKERS.filterTo(linkedSetOf()) { marker ->
            Regex("(?:^|\\s)${Regex.escape(marker)}(?:$|\\s)").containsMatchIn(normalized)
        }
    }

    private fun stripParens(value: String): String =
        value.replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.US)
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun jaccardCore(a: String, b: String): Double {
        val left = a.split(" ").filter { it.isNotBlank() }.toSet()
        val right = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    /** Candidate must be a superset/subset match of the target artist; otherwise Jaccard. */
    private fun artistScore(target: String, candidate: String): Double {
        val left = target.split(" ").filter { it.isNotBlank() }.toSet()
        val right = candidate.split(" ").filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val overlap = left.intersect(right)
        val subset = overlap.isNotEmpty() && (overlap == left || overlap == right)
        return if (subset) 1.0 else overlap.size.toDouble() / left.union(right).size
    }

    private fun parseDuration(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return when {
            value.contains(":") -> {
                val parts = value.split(":")
                val sec = parts.lastOrNull()?.toIntOrNull() ?: return null
                val min = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
                min * 60 + sec
            }
            else -> value.toIntOrNull()
        }
    }

    private fun decodeEntities(value: String?): String = (value ?: "")
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#039;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

    // ------------------------------------------------------------------ probe + cooldown

    private suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=0-4095").get().build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 429 || resp.code in 500..599) {
                    markHostDown("probe HTTP ${resp.code}")
                    return@use false
                }
                if (resp.code != 200 && resp.code != 206) return@use false
                val type = resp.body?.contentType()?.toString().orEmpty().lowercase(Locale.US)
                !type.contains("text/html")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Probe failed: ${e.message}")
            false
        }
    }

    private fun hostIsDown(): Boolean {
        val until = hostDownUntil["host"]
        if (until != null && until > System.currentTimeMillis()) {
            Log.d(TAG, "Host in cooldown, skipping")
            return true
        }
        if (until != null) hostDownUntil.remove("host")
        return false
    }

    private fun hostIsUp(): Boolean = !hostIsDown()

    private fun markHostDown(reason: String) {
        hostDownUntil["host"] = System.currentTimeMillis() + HOST_COOLDOWN_MS
        Log.w(TAG, "Host marked down ($reason), cooldown ${HOST_COOLDOWN_MS / 1000}s")
    }
}