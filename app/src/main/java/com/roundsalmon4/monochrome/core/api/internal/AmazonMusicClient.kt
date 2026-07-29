package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class AmazonStreamResult(
    val url: String,
    val sourceUrl: String,
    val decryptionKey: String?,
    val keyId: String?
)

@Singleton
class AmazonMusicClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Amazon"
        private const val API_BASE = "https://amz.geeked.wtf"
    }

    private val gson = Gson()
    private var cachedJwt: String? = null
    private var jwtExpiry: Long = 0L

    fun setJwt(jwt: String, expiresAt: Long) {
        cachedJwt = jwt
        jwtExpiry = expiresAt
        Log.d(TAG, "JWT set, expires at $expiresAt")
    }

    suspend fun getStreamUrl(trackId: String): AmazonStreamResult? {
        val jwt = getValidJwt() ?: run {
            Log.w(TAG, "No valid JWT available")
            return null
        }

        val req = okhttp3.Request.Builder()
            .url("$API_BASE/api/track/?id=$trackId&quality=HD")
            .header("X-Turnstile-JWT", jwt)
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }

        if (resp.code == 401 || resp.code == 428) {
            Log.w(TAG, "JWT expired or rejected (${resp.code})")
            cachedJwt = null
            return null
        }
        if (!resp.isSuccessful) {
            Log.w(TAG, "Amazon API: HTTP ${resp.code}")
            return null
        }

        val raw = gson.fromJson(resp.body?.string(), Map::class.java)
        val data = (raw["data"] as? Map<*, *>) ?: (raw["track"] as? Map<*, *>) ?: raw
        val streamUrl = data["stream_url"]?.toString() ?: data["url"]?.toString() ?: return null
        Log.d(TAG, "Got stream URL")
        return AmazonStreamResult(
            url = streamUrl, sourceUrl = streamUrl,
            decryptionKey = data["decryption_key"]?.toString(),
            keyId = null
        )
    }

    private fun getValidJwt(): String? {
        if (cachedJwt != null && System.currentTimeMillis() < jwtExpiry) return cachedJwt
        return null
    }
}
