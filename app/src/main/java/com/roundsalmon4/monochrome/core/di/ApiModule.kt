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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    private val API_INSTANCES = listOf(
    "https://hifi.geeked.wtf/",
    "https://eu-central.monochrome.tf/",
    "https://us-west.monochrome.tf/",
    "https://api.monochrome.tf/",
    "https://monochrome-api.samidy.com/"
)
private const val DEFAULT_API_URL = "https://hifi.geeked.wtf/"

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
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        val gson = GsonBuilder().setLenient().create()
        return Retrofit.Builder()
            .baseUrl(DEFAULT_API_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideTidalApiService(retrofit: Retrofit): TidalApiService {
        return retrofit.create(TidalApiService::class.java)
    }
}
