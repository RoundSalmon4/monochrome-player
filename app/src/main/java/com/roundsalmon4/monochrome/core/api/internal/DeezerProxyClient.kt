package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeezerProxyClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Deezer"
        private const val PROXY_BASE = "https://dzr.tabs-vs-spaces.wtf"
        private const val ORIGIN = "https://monochrome.tf"
        private const val REFERER = "https://monochrome.tf/"
        private const val FORMAT = "FLAC"
    }

    private val proxyClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    var wasNotFound: Boolean = false

    suspend fun getStreamUrl(isrc: String): String? {
        wasNotFound = false
        val url = "$PROXY_BASE/stream/?isrc=${java.net.URLEncoder.encode(isrc, "UTF-8")}&format=$FORMAT"
        Log.d(TAG, "Trying ISRC=$isrc")

        val headResult = try {
            val req = Request.Builder().url(url)
                .head()
                .header("Origin", ORIGIN)
                .header("Referer", REFERER)
                .build()
            withContext(Dispatchers.IO) {
                proxyClient.newCall(req).execute().use { resp ->
                    Log.d(TAG, "HEAD: HTTP ${resp.code}")
                    resp.isSuccessful || resp.code == 405 || resp.code == 501
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed: ${e.message}")
            false
        }
        if (headResult) return url

        // Fallback: try GET instead of HEAD (some proxies reject HEAD)
        val getResult = try {
            val req = Request.Builder().url(url)
                .header("Origin", ORIGIN)
                .header("Referer", REFERER)
                .build()
            withContext(Dispatchers.IO) {
                proxyClient.newCall(req).execute().use { resp ->
                    Log.d(TAG, "GET: HTTP ${resp.code}")
                    resp.isSuccessful
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET failed: ${e.message}")
            false
        }
        if (getResult) return url

        wasNotFound = true
        return null
    }
}
