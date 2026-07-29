package com.roundsalmon4.monochrome.core.api.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class AmazonStreamResult(
    val url: String,
    val sourceUrl: String,
    val decryptionKey: String?,
    val keyId: String?
)

@Singleton
class AmazonMusicClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-Amazon"
        private const val API_BASE = "https://amz.geeked.wtf"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAADgxqF6QVMm0GLHH"
    }

    private val gson = Gson()
    private var cachedJwt: String? = null
    private var jwtExpiry: Long = 0L

    suspend fun getStreamUrl(trackId: String): AmazonStreamResult? {
        val jwt = getTurnstileJwt() ?: return null
        val req = okhttp3.Request.Builder()
            .url("$API_BASE/api/track/?id=$trackId&quality=HD")
            .header("X-Turnstile-JWT", jwt)
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (!resp.isSuccessful) {
            Log.w(TAG, "Amazon API: ${resp.code}")
            if (resp.code == 401 || resp.code == 428) cachedJwt = null
            return null
        }
        val raw = gson.fromJson(resp.body?.string(), Map::class.java)
        val data = (raw["data"] as? Map<*, *>) ?: (raw["track"] as? Map<*, *>) ?: raw
        val streamUrl = data["stream_url"]?.toString() ?: data["url"]?.toString() ?: return null
        return AmazonStreamResult(
            url = streamUrl, sourceUrl = streamUrl,
            decryptionKey = data["decryption_key"]?.toString(),
            keyId = null
        )
    }

    private suspend fun getTurnstileJwt(): String? {
        if (cachedJwt != null && System.currentTimeMillis() < jwtExpiry) return cachedJwt
        try {
            val token = withTimeout(15000L) { obtainTurnstileToken() }
            if (token == null) { Log.w(TAG, "No Turnstile token"); return null }
            val jwt = exchangeTokenForJwt(token) ?: return null
            cachedJwt = jwt; jwtExpiry = System.currentTimeMillis() + 55 * 60 * 1000L
            return jwt
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Turnstile timed out"); return null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun obtainTurnstileToken(): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onToken(token: String) {
                        Handler(Looper.getMainLooper()).post {
                            webView.destroy()
                            if (!cont.isCompleted) cont.resume(token)
                        }
                    }
                }, "Android")

                val html = """<!DOCTYPE html><html>
<head><meta charset="utf-8">
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onLoad" async defer></script>
</head><body><div id="c"></div>
<script>function onLoad(){turnstile.render('#c',{sitekey:'$TURNSTILE_SITE_KEY',callback:function(t){Android.onToken(t);}});}</script>
</body></html>"""
                webView.loadDataWithBaseURL(API_BASE, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "WebView error", e)
                if (!cont.isCompleted) cont.resume(null)
            }
        }
    }

    private suspend fun exchangeTokenForJwt(token: String): String? {
        val body = FormBody.Builder().add("turnstile_response", token).build()
        val req = okhttp3.Request.Builder().url("$API_BASE/api/auth/turnstile").post(body).build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (!resp.isSuccessful) return null
        val json = resp.body?.string() ?: return null
        val data = gson.fromJson(json, Map::class.java)
        return data["jwt"]?.toString() ?: data["token"]?.toString()
    }
}
