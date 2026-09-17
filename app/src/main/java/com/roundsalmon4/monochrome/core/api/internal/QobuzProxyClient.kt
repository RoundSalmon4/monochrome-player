package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Multi-backend Qobuz lossless resolver. Ported from the architecture used by
 * the Meld (Metrolist) client: several independent community resolvers with
 * per-host cooldowns, captcha lockouts, an ISRC-first matching heuristic and a
 * quality-tier fallback ladder (Hi-Res -> CD -> AAC 320).
 *
 * Every backend shares the same Qobuz track ID space, so we can search once
 * across all backends to find a track, then stream that ID from whichever
 * backend answers. Dead/offline hosts are skipped for HOST_COOLDOWN_MS so a
 * dead proxy can't stall the whole streaming chain.
 */
@Singleton
class QobuzProxyClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Qobuz"

        private val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private data class Backend(val key: String, val base: String)

        private val BACKENDS = listOf(
            Backend("squid", "https://qobuz.squid.wtf"),
            Backend("monokenny", "https://qobuz.kennyy.com.br"),
            Backend("trypt", "https://trypt-hifi-dl-456461932686.us-west1.run.app"),
            Backend("jumo", "https://jumo-dl.pages.dev")
        )

        private const val DEFAULT_COUNTRY = "US"
        private const val HOST_COOLDOWN_MS = 3 * 60 * 1000L
        private const val CAPTCHA_COOLDOWN_MS = 5 * 60 * 1000L
        private const val STREAM_CACHE_MS = 5 * 60 * 1000L
        private const val REJECT_SCORE = -1_000_000
        private const val MIN_ACCEPT_SCORE = 330

        private val QUALITY_LADDER = intArrayOf(27, 6, 5) // Hi-Res, CD, AAC 320
    }

    private val proxyClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    var wasNotFound: Boolean = false

    private data class TrackMatch(val id: String)

    private data class Query(
        val isrc: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private val hostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val captchaCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val trackCache = ConcurrentHashMap<String, TrackMatch>()
    private val streamCache = ConcurrentHashMap<String, String>()
    private val streamCacheUntil = ConcurrentHashMap<String, Long>()

    /** Searches each backend by every term and returns the best-scoring match, or null. */
    suspend fun getStreamUrl(
        isrc: String,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L
    ): String? {
        wasNotFound = false
        val query = Query(isrc.trim().uppercase(Locale.US), title, artist, album, durationMs)
        val terms = buildSearchTerms(query)

        val match = findMatch(query, terms)
            ?: run {
                Log.w(TAG, "No Qobuz match for '${query.title}' (isrc=${query.isrc})")
                return null
            }

        for (quality in QUALITY_LADDER) {
            for (backend in BACKENDS) {
                val url = requestStream(match.id, quality, backend) ?: continue
                streamCache["${match.id}|$quality|${backend.key}"] = url
                streamCacheUntil["${match.id}|$quality|${backend.key}"] =
                    System.currentTimeMillis() + STREAM_CACHE_MS
                Log.d(TAG, "Resolved track ${match.id} via ${backend.key} quality=$quality")
                return url
            }
        }
        Log.w(TAG, "Qobuz stream ladder exhausted for track ${match.id}")
        wasNotFound = true
        return null
    }

    // ------------------------------------------------------------------ search

    private fun buildSearchTerms(query: Query): List<String> = buildList {
        if (query.isrc.isNotBlank()) add(query.isrc)
        if (query.title.isNotBlank()) {
            add(query.title)
            if (query.artist.isNotBlank()) add("${query.title} ${query.artist}")
        }
    }.distinct()

    private suspend fun findMatch(query: Query, terms: List<String>): TrackMatch? {
        var anyHostServed = false
        for (term in terms) {
            val cacheKey = term.lowercase(Locale.US)
            trackCache[cacheKey]?.let { return it }
            for (backend in BACKENDS) {
                val items = search(term, backend) ?: continue
                anyHostServed = true
                val best = selectBestTrack(items, query) ?: continue
                trackCache[cacheKey] = best
                Log.d(TAG, "Matched '${query.title}' via ${backend.key} (term='$term' -> ${best.id})")
                return best
            }
        }
        // A catalog gap only exists when at least one backend actually answered.
        wasNotFound = anyHostServed
        return null
    }

    private suspend fun search(term: String, backend: Backend): List<Map<String, Any?>>? {
        if (isHostCooling(backend)) return null
        val url = searchUrl(backend, term) ?: return null
        val body = httpGet(url, backend) ?: run {
            return null
        }
        if (looksLikeHtml(body)) {
            markHostDown(backend, "HTML response (offline/maintenance page)")
            return null
        }
        val items = runCatching {
            val root = gson.fromJson(body, Map::class.java)
            (root["data"] as? Map<*, *>)
                ?.let { it["tracks"] as? Map<*, *> }
                ?.let { it["items"] as? List<*> }
                ?.mapNotNull { item -> item as? Map<String, Any?> }
        }.getOrNull()
        if (items == null) {
            Log.w(TAG, "Search from ${backend.key} returned no items: ${body.take(160)}")
            return null
        }
        return items
    }

    private fun searchUrl(backend: Backend, term: String): String? {
        val builder = when (backend.key) {
            "jumo" -> backend.base.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("query", term)
                ?.addQueryParameter("offset", "0")
                ?.addQueryParameter("limit", "30")
                ?.addQueryParameter("region", DEFAULT_COUNTRY)
            else -> backend.base.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", term)
                ?.addQueryParameter("offset", "0")
        }
        return builder?.build()?.toString()
    }

    private fun selectBestTrack(items: List<Map<String, Any?>>, query: Query): TrackMatch? {
        val wantedTitle = normalize(query.title)
        val wantedArtists = query.artist.split('&', ',', ';', '(', ')').map { normalize(it) }.filter { it.isNotBlank() }
        val wantedAlbum = normalize(query.album)
        val wantedIsrc = query.isrc
        val wantedDurationSec = if (query.durationMs > 0) (query.durationMs / 1000L).toInt() else null

        var best: TrackMatch? = null
        var bestScore = REJECT_SCORE

        for (item in items) {
            val id = item["id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val candidateTitle = normalize(combineTitle(item["title"], item["version"]))
            val candidateAlbum = normalize((item["album"] as? Map<*, *>)?.get("title")?.toString())
            val candidateIsrc = item["isrc"]?.toString()?.trim()?.uppercase(Locale.US).orEmpty()
            val candidateDuration = item["duration"]?.toString()?.toIntOrNull()
            val candidateArtists = artistNames(item)
                .map(::normalize)
                .filter { it.isNotBlank() }
            val streamable = item["streamable"] == true
            val downloadable = item["downloadable"] == true

            val titleTokens = significantTokens(wantedTitle)
            val candidateTokens = significantTokens(candidateTitle)
            val matchedTokens = titleTokens.count(candidateTokens::contains)

            if (wantedDurationSec != null && candidateDuration != null &&
                abs(wantedDurationSec - candidateDuration) > 25
            ) continue
            if (titleTokens.isEmpty() && candidateTokens.isEmpty() && candidateTitle != wantedTitle) continue

            var score = 0
            if (wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc) score += 1000
            if (wantedTitle.isNotBlank()) {
                score += when {
                    candidateTitle.isNotBlank() && candidateTitle == wantedTitle -> 360
                    candidateTitle.isNotBlank() && (candidateTitle in wantedTitle || wantedTitle.contains(candidateTitle)) -> 180
                    matchedTokens >= 3 -> 110
                    else -> -120
                }
            }
            if (titleTokens.isNotEmpty()) {
                score += when {
                    matchedTokens == titleTokens.size -> 180
                    matchedTokens >= (titleTokens.size - 1) -> 70
                    else -> -180
                }
            }
            if (wantedArtists.isNotEmpty()) {
                val exact = wantedArtists.any { w -> candidateArtists.any { it == w } }
                val partial = wantedArtists.any { w -> candidateArtists.any { c -> artistNamesMatch(w, c) } }
                score += when {
                    exact -> 260 + ((wantedArtists.size - 1) * 55)
                    partial -> 120
                    else -> REJECT_SCORE
                }
            }
            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 160
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 60
                    else -> -50
                }
            }
            if (wantedDurationSec != null && candidateDuration != null) {
                val diff = abs(wantedDurationSec - candidateDuration)
                score += when {
                    diff <= 2 -> 180
                    diff <= 5 -> 120
                    diff <= 10 -> 50
                    diff <= 15 -> 10
                    else -> -90
                }
            }
            if (!streamable && !downloadable) score -= 25

            val isrcBoost = wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc
            if (score > REJECT_SCORE && (score >= MIN_ACCEPT_SCORE || isrcBoost) && score > bestScore) {
                best = TrackMatch(id)
                bestScore = score
            }
        }
        return best
    }

    private fun combineTitle(title: String?, version: String?): String =
        listOf(title, version).filter { it.isNotBlank() }.joinToString(" ")

    private fun artistNames(item: Map<String, Any?>): List<String> {
        val artists = item["artists"] as? List<*> ?: item["artist"]?.let { listOf(it) } ?: return emptyList()
        return artists.mapNotNull { a ->
            when (a) {
                is Map<*, *> -> a["name"]?.toString()
                else -> a?.toString()
            }
        }
    }

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val lowered = value.lowercase(Locale.US)
        return lowered.replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")
    }

    private fun significantTokens(normalized: String): List<String> =
        normalized.split(" ").filter { it.length > 2 }

    private fun artistNamesMatch(a: String, b: String): Boolean =
        a.contains(b) || b.contains(a)

    // ------------------------------------------------------------------ stream

    private suspend fun requestStream(trackId: String, quality: Int, backend: Backend): String? {
        val cacheKey = "$trackId|$quality|${backend.key}"
        streamCache[cacheKey]?.let { cached ->
            val until = streamCacheUntil[cacheKey] ?: 0L
            if (until > System.currentTimeMillis()) return cached
            streamCache.remove(cacheKey)
            streamCacheUntil.remove(cacheKey)
        }
        if (isHostCooling(backend)) return null
        captchaCooldownUntilMs[backend.key]?.let { cooldownUntil ->
            if (cooldownUntil > System.currentTimeMillis()) {
                Log.d(TAG, "Backend ${backend.key} in captcha cooldown, skipping")
                return null
            }
            captchaCooldownUntilMs.remove(backend.key)
        }

        val url = streamUrl(backend, trackId, quality) ?: return null
        val body = httpGet(url, backend) ?: return null
        if (looksLikeHtml(body)) {
            markHostDown(backend, "HTML response (offline/maintenance page)")
            return null
        }

        val root = runCatching { gson.fromJson(body, Map::class.java) }.getOrNull()
        if (root == null) return null

        val data = root["data"] as? Map<*, *>
        val streamUrl = data?.get("url")?.toString()
            ?: root["url"]?.toString()
            ?: root["directUrl"]?.toString()
        if (!streamUrl.isNullOrBlank()) {
            return streamUrl
        }

        val error = (root["error"]?.toString() ?: root["message"]?.toString() ?: "").lowercase(Locale.US)
        when {
            error.contains("captcha") -> {
                captchaCooldownUntilMs[backend.key] = System.currentTimeMillis() + CAPTCHA_COOLDOWN_MS
                Log.w(TAG, "Backend ${backend.key} captcha-blocked, cooldown ${CAPTCHA_COOLDOWN_MS / 60_000}min")
            }
            error.contains("preview") || root["previewDetected"] == true ->
                Log.d(TAG, "Backend ${backend.key} returned preview at quality $quality")
            error.contains("cooling down") || error.contains("offline") ||
                (root["success"] == false && error.contains("http 5")) -> {
                markHostDown(backend, error)
            }
            else -> Log.d(TAG, "Stream from ${backend.key} failed at quality $quality: ${body.take(120)}")
        }
        return null
    }

    private fun streamUrl(backend: Backend, trackId: String, quality: Int): String? {
        val builder = when (backend.key) {
            "jumo" -> backend.base.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("track_id", trackId)
                ?.addQueryParameter("format_id", quality.toString())
                ?.addQueryParameter("region", DEFAULT_COUNTRY)
            else -> backend.base.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("track_id", trackId)
                ?.addQueryParameter("quality", quality.toString())
        }
        return builder?.build()?.toString()
    }

    // ------------------------------------------------------------------ infra

    private suspend fun httpGet(url: String, backend: Backend): String? {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Referer", "${backend.base}/")
            .header("User-Agent", BROWSER_UA)
        if (backend.key == "squid" || backend.key == "trypt") {
            requestBuilder.header("Token-Country", DEFAULT_COUNTRY)
        }
        return withContext(Dispatchers.IO) {
            try {
                proxyClient.newCall(requestBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code in 500..599) markHostDown(backend, "HTTP ${resp.code}")
                        val short = resp.body?.string()?.take(120)
                        Log.w(TAG, "HTTP ${resp.code} from ${backend.key}: $short")
                        return@use null
                    }
                    resp.body?.string()
                }
            } catch (e: Exception) {
                if (isHostUnreachable(e)) markHostDown(backend, e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "Request to ${backend.key} failed: ${e.message}")
                null
            }
        }
    }

    private fun isHostUnreachable(e: Exception): Boolean =
        e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is java.io.InterruptedIOException ||
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true

    private fun looksLikeHtml(payload: String): Boolean {
        val first = payload.trimStart().firstOrNull() ?: return true
        return first == '<'
    }

    private fun hostOf(backend: Backend): String = backend.base.toHttpUrlOrNull()?.host ?: backend.key

    private fun isHostCooling(backend: Backend): Boolean {
        val host = hostOf(backend)
        val until = hostCooldownUntilMs[host] ?: return false
        if (until > System.currentTimeMillis()) return true
        hostCooldownUntilMs.remove(host)
        return false
    }

    private fun markHostDown(backend: Backend, reason: String) {
        hostCooldownUntilMs[hostOf(backend)] = System.currentTimeMillis() + HOST_COOLDOWN_MS
        Log.w(TAG, "Host ${hostOf(backend)} marked down ($reason), cooldown ${HOST_COOLDOWN_MS / 60_000}min")
    }
}