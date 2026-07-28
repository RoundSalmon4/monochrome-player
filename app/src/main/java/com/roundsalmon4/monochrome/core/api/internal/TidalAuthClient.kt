package com.roundsalmon4.monochrome.core.api.internal

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TidalToken(
    val accessToken: String,
    val expiresIn: Long,
    val expiryTime: Long
)

@Singleton
class TidalAuthClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var cachedToken: TidalToken? = null
    private val mutex = Mutex()

    suspend fun getToken(): String = mutex.withLock {
        cachedToken?.let { if (System.currentTimeMillis() < it.expiryTime) return@withLock it.accessToken }

        val clientId = "txNoH4kkV41MfH25"
        val clientSecret = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="

        val basic = "Basic " + Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.DEFAULT).trim()

        val body = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = okhttp3.Request.Builder()
            .url("https://auth.tidal.com/v1/oauth2/token")
            .header("Authorization", basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()

        val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            throw RuntimeException("TIDAL auth failed: ${response.code} ${response.body?.string()}")
        }

        val json = response.body?.string() ?: throw RuntimeException("Empty auth response")
        val data = gson.fromJson(json, TokenResponse::class.java)
        val expiresIn = data.expiresIn?.toLong() ?: 3600L
        val token = TidalToken(
            accessToken = data.accessToken ?: throw RuntimeException("No access token"),
            expiresIn = expiresIn,
            expiryTime = System.currentTimeMillis() + (expiresIn - 120) * 1000L
        )
        cachedToken = token
        return@withLock token.accessToken
    }

    data class TokenResponse(
        @SerializedName("access_token") val accessToken: String? = null,
        @SerializedName("expires_in") val expiresIn: Number? = null,
        @SerializedName("token_type") val tokenType: String? = null
    )
}
