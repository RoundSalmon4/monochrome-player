package com.roundsalmon4.monochrome.core.api.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed class MonochromeSessionStatus {
    data object Unknown : MonochromeSessionStatus()
    data object Valid : MonochromeSessionStatus()
    data object Expired : MonochromeSessionStatus()
    data class Refreshing(val lastError: String? = null) : MonochromeSessionStatus()
    data class Failed(val error: String) : MonochromeSessionStatus()
}

@Singleton
class MonochromeSessionRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val prefs: PlayerPreferences
) {
    companion object {
        private const val TAG = "ChromePlayer-MonoSession"
        private const val ORIGIN = "https://monochrome.tf"
        private const val API_BASE = "https://track-api.monochrome.tf"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAADgxqF6QVMm0GLHH"
        private const val REFRESH_BUFFER_MS = 10 * 60 * 1000L
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val TURNSTILE_TIMEOUT_MS = 30_000L
        private val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshMutex = Mutex()
    private val _status = MutableStateFlow<MonochromeSessionStatus>(MonochromeSessionStatus.Unknown)
    val status: StateFlow<MonochromeSessionStatus> = _status.asStateFlow()
    private var autoRefreshStarted = false

    /** Returns a usable session token, obtaining or refreshing one silently if the stored one is missing or close to expiry. */
    suspend fun getValidToken(): String? {
        val (jwt, expiry) = prefs.getMonochromeJwt() ?: return refresh()
        if (jwt.isNotBlank() && expiry > System.currentTimeMillis() + 60_000L) {
            _status.value = MonochromeSessionStatus.Valid
            return jwt
        }
        return refresh()
    }

    /** Start a background loop that keeps the session fresh so playback is never interrupted. */
    fun startAutoRefresh() {
        if (autoRefreshStarted) return
        autoRefreshStarted = true
        scope.launch {
            while (true) {
                val (jwt, expiry) = prefs.getMonochromeJwt() ?: Pair("", 0L)
                if (jwt.isNotBlank() && expiry > System.currentTimeMillis() + REFRESH_BUFFER_MS) {
                    _status.value = MonochromeSessionStatus.Valid
                } else {
                    Log.i(TAG, "No valid session, refreshing")
                    refresh()
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Force a fresh session exchange through the Turnstile WebView flow. Safe to call concurrently. */
    suspend fun refresh(): String? = refreshMutex.withLock {
        _status.value = MonochromeSessionStatus.Refreshing()
        try {
            val (cachedJwt, cachedExpiry) = prefs.getMonochromeJwt() ?: Pair("", 0L)
            if (cachedJwt.isNotBlank() && cachedExpiry > System.currentTimeMillis() + REFRESH_BUFFER_MS) {
                _status.value = MonochromeSessionStatus.Valid
                return cachedJwt
            }
            val turnstileToken = withTimeout(TURNSTILE_TIMEOUT_MS) { runTurnstile() }
            if (turnstileToken == null) {
                _status.value = MonochromeSessionStatus.Failed("Turnstile challenge failed")
                return null
            }
            val (jwt, expiry) = exchangeTokenForJwt(turnstileToken)
            if (jwt == null) {
                _status.value = MonochromeSessionStatus.Failed("Session exchange failed")
                return null
            }
            prefs.setMonochromeJwt(jwt, expiry)
            _status.value = MonochromeSessionStatus.Valid
            Log.i(TAG, "Session refreshed, expires ${formatExpiry(expiry)}")
            jwt
        } catch (e: Exception) {
            Log.w(TAG, "Session refresh failed: ${e.message}")
            _status.value = MonochromeSessionStatus.Failed(e.message ?: "Unknown error")
            null
        }
    }

    suspend fun setManualJwt(jwt: String) {
        prefs.setMonochromeJwt(jwt, jwtExpiryMillis(jwt))
        _status.value = MonochromeSessionStatus.Valid
    }

    private fun jwtExpiryMillis(jwt: String): Long {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return 0L
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized.padEnd(normalized.length + (4 - normalized.length % 4) % 4, '=')
            val json = String(Base64.decode(padded, Base64.DEFAULT))
            val exp = gson.fromJson(json, Map::class.java)["exp"]
            ((exp as? Number)?.toLong() ?: 0L) * 1000L
        } catch (_: Exception) { 0L }
    }

    private fun formatExpiry(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private suspend fun exchangeTokenForJwt(token: String): Pair<String?, Long> {
        val body = "{\"turnstile_token\":${gson.toJson(token)}}"
        val request = Request.Builder()
            .url("$API_BASE/auth/turnstile")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        if (!resp.isSuccessful) {
            Log.w(TAG, "auth/turnstile: HTTP ${resp.code}")
            return Pair(null, 0L)
        }
        val data = resp.body?.string()?.let {
            runCatching {
                gson.fromJson<Map<String, Any?>>(it, object : TypeToken<Map<String, Any?>>() {}.type)
            }.getOrNull()
        } ?: return Pair(null, 0L)
        val jwt = data["access_token"]?.toString()?.takeIf { it.isNotBlank() } ?: return Pair(null, 0L)
        return Pair(jwt, jwtExpiryMillis(jwt))
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
        var widgetId = turnstile.render('#c', {
            sitekey: '$TURNSTILE_SITE_KEY',
            size: 'invisible',
            execution: 'execute',
            action: 'auth',
            callback: function(t){ Android.onToken(t); },
            'error-callback': function(e){ Android.onError('Turnstile error: '+e); },
            'expired-callback': function(){ Android.onError('Turnstile expired'); }
        });
        turnstile.execute(widgetId);
    } catch(e) { Android.onError('Exception: '+e.message); }
}
</script></body></html>"""

                webView.loadDataWithBaseURL(ORIGIN, html, "text/html", "UTF-8", null)

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
