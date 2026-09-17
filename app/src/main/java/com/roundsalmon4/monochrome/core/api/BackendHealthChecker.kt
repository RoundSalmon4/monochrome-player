package com.roundsalmon4.monochrome.core.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes every backend the player can talk to and reports a simple up/down
 * status so the Settings screen can render a green/red indicator per endpoint.
 * Checks run concurrently with a short timeout and are purely read-only.
 */
@Singleton
class BackendHealthChecker @Inject constructor(
    okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Health"

        internal val METADATA_INSTANCES = listOf(
            "eu-central.monochrome.tf",
            "us-west.monochrome.tf",
            "arran.monochrome.tf",
            "api.monochrome.tf",
            "monochrome-api.samidy.com",
            "triton.squid.wtf",
            "wolf.qqdl.site",
            "maus.qqdl.site",
            "vogel.qqdl.site",
            "hund.qqdl.site",
            "tidal.kinoplus.online"
        )
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Target(
        val group: String,
        val name: String,
        val url: String,
        val method: String = "GET",
        val authHeader: String? = null,
        val htmlMeansDown: Boolean = false
    )

    data class Result(
        val target: Target,
        val up: Boolean,
        val detail: String
    )

    val targets: List<Target> = buildList {
        METADATA_INSTANCES.forEach { host ->
            add(
                Target(
                    group = "Metadata",
                    name = host,
                    url = "https://$host/search/?s=health&limit=1"
                )
            )
        }
        add(Target("Home", "hot.monochrome.tf", "https://hot.monochrome.tf/"))
        add(Target("Playback", "Monochrome session", "https://track-api.monochrome.tf/config"))
        add(Target("Playback", "Monochrome CDN", "https://tracks.monochrome.tf/"))
        add(Target("Playback", "Unified (geeked)", "https://music-api.geeked.wtf/"))
        add(Target("Playback", "Amazon (geeked)", "https://amz.geeked.wtf/"))
        add(Target("Sources", "SoundCloud", "https://soundcloud.com/"))
        add(Target("Sources", "Qobuz - Squid", "https://qobuz.squid.wtf/api/get-music?q=health"))
        add(Target("Sources", "Qobuz - Monokenny", "https://qobuz.kennyy.com.br/api/get-music?q=health"))
        add(
            Target(
                "Sources",
                "Qobuz - TrypT",
                "https://trypt-hifi-dl-456461932686.us-west1.run.app/api/get-music?q=health"
            )
        )
        add(
            Target(
                "Sources",
                "Qobuz - Jumo",
                "https://jumo-dl.pages.dev/search?query=health",
                htmlMeansDown = true
            )
        )
        add(
            Target(
                "Sources",
                "Deezer proxy",
                "https://dzr.tabs-vs-spaces.wtf/stream/?isrc=health&format=FLAC",
                method = "HEAD"
            )
        )
        add(
            Target(
                "Sources",
                "Internet Archive",
                "https://archive.org/advancedsearch.php?q=health&rows=1&output=json"
            )
        )
        add(
            Target(
                "Sources",
                "JioSaavn",
                "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&query=health"
            )
        )
    }

    /** Runs every check concurrently, invoking [onResult] as each one finishes. */
    suspend fun checkAll(onResult: suspend (Result) -> Unit) = coroutineScope {
        targets.map { target ->
            async(Dispatchers.IO) {
                val result = check(target)
                onResult(result)
                result
            }
        }.forEach { it.await() }
    }

    private suspend fun check(target: Target): Result = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(target.url)
            target.authHeader?.let { builder.header("Authorization", it) }
            val request = if (target.method == "HEAD") builder.head().build() else builder.get().build()
            client.newCall(request).execute().use { resp ->
                val bodySnippet = if (target.method == "GET" && target.htmlMeansDown) {
                    resp.body?.string().orEmpty().trimStart().take(1)
                } else null
                val htmlDown = bodySnippet == "<"
                when {
                    htmlDown -> Result(target, false, "HTTP ${resp.code} (maintenance page)")
                    resp.code in 200..399 -> Result(target, true, "HTTP ${resp.code}")
                    resp.code == 401 || resp.code == 403 -> Result(target, false, "HTTP ${resp.code} (auth/blocked)")
                    else -> Result(target, false, "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "check ${target.name} failed: ${e.message}")
            val reason = when (e) {
                is java.net.UnknownHostException -> "DNS fail"
                is java.net.ConnectException -> "connection refused"
                is java.io.InterruptedIOException -> "timeout"
                else -> e.javaClass.simpleName
            }
            Result(target, false, reason)
        }
    }
}