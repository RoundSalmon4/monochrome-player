package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.roundsalmon4.monochrome.core.api.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/** Holds every pluggable [DiscoverySource] and reports which ones are currently available. */
@Singleton
class SourceDiscoveryRegistry @Inject constructor(
    soundCloud: SoundCloudSource,
    jioSaavn: JioSaavnSource,
    internetArchive: InternetArchiveSource
) {
    companion object {
        private const val TAG = "ChromePlayer-Discovery"
    }

    val all: List<DiscoverySource> = listOf(soundCloud, jioSaavn, internetArchive)

    suspend fun available(): List<DiscoverySource> {
        val available = all.filter { it.isAvailable() }
        Log.i(TAG, "available sources: ${available.joinToString { it.displayName }}")
        return available
    }

    /**
     * Resolves every [DiscoveredItem] of [source] to a playable [Track] (with a
     * pre-resolved direct stream, so the playback chain is bypassed entirely).
     * Items that fail resolution are dropped.
     */
    suspend fun resolveQueue(source: DiscoverySource, items: List<DiscoveredItem>): List<Track> {
        val semaphore = Semaphore(3)
        val start = System.currentTimeMillis()
        val resolved = coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        item to runCatching { source.resolveStream(item) }.getOrNull()
                    }
                }
            }.awaitAll()
        }
        val tracks = resolved.mapNotNull { (item, stream) ->
            stream?.let { s ->
                Track(
                    id = item.id,
                    title = item.title,
                    artistName = item.artist,
                    artistId = "",
                    albumId = item.id,
                    albumTitle = item.albumTitle ?: "",
                    coverUrl = item.artworkUrl,
                    durationMs = item.durationMs,
                    directStreamUrl = s.url,
                    directMimeType = s.mimeType
                )
            }
        }
        Log.i(TAG, "resolved ${tracks.size}/${items.size} from ${source.displayName} in ${System.currentTimeMillis() - start}ms")
        return tracks
    }
}