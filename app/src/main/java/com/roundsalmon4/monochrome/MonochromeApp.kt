package com.roundsalmon4.monochrome

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MonochromeApp : Application(), SingletonImageLoader.Factory {
    private var sharedOkHttpClient: OkHttpClient? = null

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = sharedOkHttpClient ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .also { sharedOkHttpClient = it }
        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .build()
    }
}
