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
enum class Availability { UNKNOWN, CHECKING, ALL_AVAILABLE, SOME_AVAILABLE, NONE_AVAILABLE }

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
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val SOURCE_COOLDOWN_MS = 60_000L
        private const val MAX_CONCURRENT = 3
    }

    private class Entry(val available: Boolean, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** A source that fails with an infra error (timeout/exception) is skipped for a while. */
    private val sourceCooldownUntilMs = ConcurrentHashMap<String, Long>()

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
        _albumStatus.update { it + (albumId to Availability.CHECKING) }
        val completed = AtomicInteger(0)
        val availableCount = AtomicInteger(0)
        val lock = Mutex()

        coroutineScope {
            tracks.map { track ->
                async {
                    val ok = semaphore.withPermit { checkTrack(track) }
                    val (avail, done) = lock.withLock {
                        availableCount.addAndGet(if (ok) 1 else 0)
                        completed.incrementAndGet()
                        availableCount.get() to completed.get()
                    }
                    // Only conclude a state that is already certain:
                    // - all tracks available once every track is known available
                    // - "some" once at least one is available AND at least one is not
                    // - otherwise it's still checking (never prematurely report "some")
                    val status = when {
                        avail == tracks.size -> Availability.ALL_AVAILABLE
                        avail > 0 && (done - avail) > 0 -> Availability.SOME_AVAILABLE
                        done == tracks.size -> Availability.NONE_AVAILABLE
                        else -> Availability.CHECKING
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
        val coolingUntil = sourceCooldownUntilMs[name]
        if (coolingUntil != null && coolingUntil > System.currentTimeMillis()) {
            Log.d(TAG, "$name is cooling down, skipping for '${track.title}'")
            return false
        }
        val result = try {
            withTimeout(PROBE_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            // Infra failure (timeout/network): skip this source for the next checks.
            sourceCooldownUntilMs[name] = System.currentTimeMillis() + SOURCE_COOLDOWN_MS
            Log.d(TAG, "$name probe for '${track.title}' failed: ${e.message} -> cooling down ${SOURCE_COOLDOWN_MS / 1000}s")
            false
        }
        if (result) Log.d(TAG, "$name has '${track.title}'")
        return result
    }
}