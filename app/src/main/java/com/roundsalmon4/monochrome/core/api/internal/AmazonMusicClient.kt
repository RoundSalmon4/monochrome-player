package com.roundsalmon4.monochrome.core.api.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
        private val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
    }

    private val gson = Gson()
    private var cachedJwt: String? = null
    private var jwtExpiry: Long = 0L
    private var bypassToken: String? = null

    fun setJwt(jwt: String, expiresAt: Long) {
        cachedJwt = jwt; jwtExpiry = expiresAt
        Log.d(TAG, "JWT set, expires at $expiresAt")
    }

    fun setBypassToken(token: String) { bypassToken = token; Log.d(TAG, "Bypass token set") }

    suspend fun getStreamUrl(trackId: String): AmazonStreamResult? {
        val jwt = resolveJwt() ?: run { Log.w(TAG, "No JWT"); return null }
        val req = okhttp3.Request.Builder()
            .url("$API_BASE/api/track/?id=$trackId&quality=HD")
            .header("X-Turnstile-JWT", jwt)
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (resp.code == 401 || resp.code == 428) { cachedJwt = null; Log.w(TAG, "JWT rejected"); return null }
        if (!resp.isSuccessful) { Log.w(TAG, "Amazon: HTTP ${resp.code}"); return null }

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

    private suspend fun resolveJwt(): String? {
        // 1. Use cached JWT if still valid
        if (cachedJwt != null && System.currentTimeMillis() < jwtExpiry) return cachedJwt

        // 2. Try bypass token
        if (bypassToken != null) {
            val jwt = exchangeBypassForJwt(bypassToken!!)
            if (jwt != null) { cachedJwt = jwt; jwtExpiry = System.currentTimeMillis() + 55 * 60 * 1000L; return jwt }
        }

        // 3. Try WebView-based Turnstile
        try {
            val token = withTimeout(20000L) { runTurnstile() }
            if (token != null) {
                val jwt = exchangeTokenForJwt(token)
                if (jwt != null) { cachedJwt = jwt; jwtExpiry = System.currentTimeMillis() + 55 * 60 * 1000L; return jwt }
            }
        } catch (_: TimeoutCancellationException) { Log.w(TAG, "Turnstile timed out") }
        return null
    }

    private suspend fun exchangeTokenForJwt(token: String): String? {
        val body = FormBody.Builder().add("turnstile_response", token).build()
        val req = okhttp3.Request.Builder().url("$API_BASE/api/auth/turnstile").post(body).build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (!resp.isSuccessful) return null
        val data = gson.fromJson(resp.body?.string(), Map::class.java)
        return data["jwt"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private suspend fun exchangeBypassForJwt(bypass: String): String? {
        val req = okhttp3.Request.Builder()
            .url("$API_BASE/api/track/?id=1&quality=HD&bypass_token=$bypass")
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
        if (resp.code == 428) {
            // 428 means we need to verify the bypass token is valid, exchange it for a JWT
            val body = FormBody.Builder().add("bypass_token", bypass).build()
            val exchangeReq = okhttp3.Request.Builder().url("$API_BASE/api/auth/turnstile").post(body).build()
            val exchangeResp = withContext(Dispatchers.IO) { okHttpClient.newCall(exchangeReq).execute() }
            if (!exchangeResp.isSuccessful) return null
            val data = gson.fromJson(exchangeResp.body?.string(), Map::class.java)
            return data["jwt"]?.toString()?.takeIf { it.isNotBlank() }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runTurnstile(): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = CHROME_UA
                webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webView.isClickable = false

                var jsError: String? = null
                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(wv: WebView?, req: WebResourceRequest?, err: android.webkit.WebResourceError?) {
                        jsError = "WebView load error: ${err?.description}"
                    }
                }

                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onToken(token: String) {
                        postCleanup(webView) { if (!cont.isCompleted) cont.resume(token) }
                    }
                    @JavascriptInterface
                    fun onError(msg: String) {
                        postCleanup(webView) { jsError = msg; if (!cont.isCompleted) cont.resume(null) }
                    }
                }, "Android")

                val html = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=onLoad" async defer></script>
</head><body style="margin:0;background:transparent"><div id="c"></div>
<script>
function onLoad(){
    try {
        turnstile.render('#c', {
            sitekey: '$TURNSTILE_SITE_KEY',
            callback: function(t){ Android.onToken(t); },
            'error-callback': function(e){ Android.onError('Turnstile error: '+e); },
            'expired-callback': function(){ Android.onError('Turnstile expired'); }
        });
    } catch(e) { Android.onError('Exception: '+e.message); }
}
</script></body></html>"""

                webView.loadDataWithBaseURL(API_BASE, html, "text/html", "UTF-8", null)

                cont.invokeOnCancellation { postCleanup(webView) {} }
            } catch (e: Exception) {
                Log.e(TAG, "WebView setup failed", e)
                if (!cont.isCompleted) cont.resume(null)
            }
        }
    }

    private fun postCleanup(wv: WebView, action: () -> Unit = {}) {
        Handler(Looper.getMainLooper()).post { action(); wv.destroy() }
    }
}
