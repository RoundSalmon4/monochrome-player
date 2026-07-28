package com.roundsalmon4.monochrome.core.di

import com.google.gson.GsonBuilder
import com.roundsalmon4.monochrome.core.api.internal.TidalApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Named
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    @Named("api.instances")
    fun provideApiInstances(): List<String> = listOf(
        "https://api.monochrome.tf/",
        "https://monochrome-api.samidy.com/",
        "https://eu-central.monochrome.tf/",
        "https://us-west.monochrome.tf/"
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            android.util.Log.println(android.util.Log.DEBUG, "ChromePlayer-Http", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val userAgent = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            chain.proceed(request)
        }
        return OkHttpClient.Builder()
            .addInterceptor(userAgent)
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
