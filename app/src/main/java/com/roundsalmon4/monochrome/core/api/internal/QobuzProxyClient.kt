package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QobuzProxyClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Qobuz"
        private const val PROXY_BASE = "https://qobuz.kennyy.com.br"
        private const val QUALITY = "27" // HI_RES_LOSSLESS
    }

    private val proxyClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    var wasNotFound: Boolean = false

    suspend fun getStreamUrl(isrc: String): String? {
        wasNotFound = false
        Log.d(TAG, "Searching ISRC=$isrc")

        val searchUrl = "$PROXY_BASE/api/get-music?q=${java.net.URLEncoder.encode(isrc, "UTF-8")}&offset=0"
        val searchResp = withContext(Dispatchers.IO) {
            proxyClient.newCall(Request.Builder().url(searchUrl).build()).execute()
        }
        Log.d(TAG, "Search: HTTP ${searchResp.code}")
        if (!searchResp.isSuccessful) {
            Log.w(TAG, "Search failed: ${searchResp.body?.string()}")
            return null
        }
        val searchBody = searchResp.body?.string() ?: return null.also { Log.w(TAG, "Empty search body") }
        val searchData = gson.fromJson(searchBody, Map::class.java)
        Log.d(TAG, "Search response: ${searchBody.take(200)}")

        val items = (searchData["data"] as? Map<*, *>)
            ?.let { it["tracks"] as? Map<*, *> }
            ?.let { it["items"] as? List<*> }
        if (items == null) { Log.w(TAG, "No items in response"); return null }

        val match = items.firstNotNullOfOrNull { item ->
            val m = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
            if (m["isrc"]?.toString()?.lowercase() == isrc.lowercase()) m else null
        }
        if (match == null) {
            Log.w(TAG, "No ISRC match for $isrc in ${items.size} items")
            return null
        }

        // TIDAL ID guard: pure numeric IDs are TIDAL, not Qobuz
        val qobuzId = match["id"]?.toString() ?: ""
        if (qobuzId.all { it.isDigit() }) {
            Log.w(TAG, "Matched ID '$qobuzId' is pure numeric (TIDAL leak), skipping")
            return null
        }

        Log.d(TAG, "Found track $qobuzId")
        val streamResp = withContext(Dispatchers.IO) {
            proxyClient.newCall(
                Request.Builder().url("$PROXY_BASE/api/download-music?track_id=$qobuzId&quality=$QUALITY").build()
            ).execute()
        }
        Log.d(TAG, "Download: HTTP ${streamResp.code}")
        if (!streamResp.isSuccessful) {
            Log.w(TAG, "Download failed: ${streamResp.body?.string()}")
            return null
        }
        val streamBody = streamResp.body?.string() ?: return null.also { Log.w(TAG, "Empty download body") }
        val streamData = gson.fromJson(streamBody, Map::class.java)
        if (streamData["success"] == true) {
            val url = (streamData["data"] as? Map<*, *>)?.get("url")?.toString()
            Log.d(TAG, if (url != null) "Got stream URL" else "No URL in response")
            return url
        }
        Log.w(TAG, "success=false in download response")
        return null
    }
}
