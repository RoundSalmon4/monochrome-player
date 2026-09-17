package com.roundsalmon4.monochrome.core.api

import android.util.Log
import com.roundsalmon4.monochrome.core.api.internal.InternetArchiveClient
import com.roundsalmon4.monochrome.core.api.internal.JioSaavnClient
import com.roundsalmon4.monochrome.core.api.internal.QobuzProxyClient
import com.roundsalmon4.monochrome.core.api.internal.SoundCloudClient
import com.roundsalmon4.monochrome.core.api.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Aggregate availability of an album, derived from its tracks' checks. */
enum class Availability { UNKNOWN, ALL_AVAILABLE, SOME_AVAILABLE, NONE_AVAILABLE }

/**
 * Determines whether tracks can actually be streamed by probing the live
 * sources (the same clients the playback chain uses), with results cached so
 * re-opening screens doesn't re-probe. Exposes live maps so both album detail
 * (per-track dots) and album cards (aggregate dots) can react as checks finish.
 */
@Singleton
class TrackAvailabilityChecker @Inject constructor(
    private val soundCloudClient: SoundCloudClient,
    private val jioSaavnClient: JioSaavnClient,
    private val internetArchiveClient: InternetArchiveClient,
    private val qobuzProxyClient: QobuzProxyClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Availability"
        private const val TTL_MS = 10 * 60 * 1000L
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val MAX_CONCURRENT = 3
    }

    private class Entry(val available: Boolean, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private val _trackStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val trackStatus: StateFlow<Map<String, Boolean>> = _trackStatus.asStateFlow()

    private val _albumStatus = MutableStateFlow<Map<String, Availability>>(emptyMap())
    val albumStatus: StateFlow<Map<String, Availability>> = _albumStatus.asStateFlow()

    /** Cached per-track result if still fresh, else null. */
    fun cachedTrackStatus(trackId: String): Boolean? {
        val entry = cache[trackId] ?: return null
        return if (entry.expiresAt > System.currentTimeMillis()) entry.available else null
    }

    fun cachedAlbumStatus(albumId: String): Availability = _albumStatus.value[albumId] ?: Availability.UNKNOWN

    /** Checks every track of an album (bounded concurrency), publishing per-track and aggregate results live. */
    suspend fun checkAlbum(albumId: String, tracks: List<Track>) {
        Log.i(TAG, "checkAlbum $albumId: ${tracks.size} track(s)")
        if (tracks.isEmpty()) {
            _albumStatus.update { it + (albumId to Availability.NONE_AVAILABLE) }
            return
        }
        val completed = AtomicInteger(0)
        val availableCount = AtomicInteger(0)
        val lock = Mutex()

        coroutineScope {
            tracks.map { track ->
                async {
                    val ok = semaphore.withPermit { checkTrack(track) }
                    val avail = lock.withLock {
                        availableCount.addAndGet(if (ok) 1 else 0)
                        completed.incrementAndGet()
                        availableCount.get()
                    }
                    val done = completed.get()
                    val status = when {
                        avail == 0 && done == tracks.size -> Availability.NONE_AVAILABLE
                        avail == 0 -> Availability.SOME_AVAILABLE
                        avail == tracks.size && done == tracks.size -> Availability.ALL_AVAILABLE
                        else -> Availability.SOME_AVAILABLE
                    }
                    _albumStatus.update { it + (albumId to status) }
                    Log.d(TAG, "album $albumId: $done/${tracks.size} checked, $avail available -> $status")
                }
            }.forEach { it.await() }
        }
        Log.i(TAG, "checkAlbum $albumId done -> ${_albumStatus.value[albumId]}")
    }

    suspend fun checkTrack(track: Track): Boolean {
        val key = track.id
        cache[key]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis()) {
                Log.d(TAG, "track '${track.title}' (${key}): cache hit -> ${entry.available}")
                _trackStatus.update { it + (key to entry.available) }
                return entry.available
            }
        }
        Log.d(TAG, "track '${track.title}' (${key}): probing sources")
        val available = probe(track)
        cache[key] = Entry(available, System.currentTimeMillis() + TTL_MS)
        _trackStatus.update { it + (key to available) }
        Log.i(TAG, "track '${track.title}' (${key}) -> ${if (available) "AVAILABLE" else "UNAVAILABLE"}")
        return available
    }

    /** Fast source probes, stopping at the first success. Skips sources that are cooling down. */
    private suspend fun probe(track: Track): Boolean {
        if (probeSource("SoundCloud", track) { soundCloudClient.getStreamUrl(track.title, track.artistName) != null }) return true
        if (probeSource("JioSaavn", track) {
                jioSaavnClient.getStreamUrl(
                    title = track.title,
                    artist = track.artistName,
                    album = track.albumTitle,
                    durationMs = track.durationMs
                ) != null
            }
        ) return true
        if (track.isrc.isNotBlank() &&
            probeSource("Qobuz", track) {
                qobuzProxyClient.getStreamUrl(
                    isrc = track.isrc,
                    title = track.title,
                    artist = track.artistName,
                    album = track.albumTitle,
                    durationMs = track.durationMs
                ) != null
            }
        ) return true
        if (probeSource("InternetArchive", track) {
                internetArchiveClient.getStreamUrl(track.title, track.artistName) != null
            }
        ) return true
        Log.d(TAG, "no source has '${track.title}'")
        return false
    }

    private suspend fun probeSource(name: String, track: Track, block: suspend () -> Boolean): Boolean {
        val result = try {
            withTimeout(PROBE_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            Log.d(TAG, "$name probe for '${track.title}' failed: ${e.message}")
            false
        }
        if (result) Log.d(TAG, "$name has '${track.title}'")
        return result
    }
}