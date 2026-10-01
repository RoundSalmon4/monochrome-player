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

    // Mirrors upstream public/instances.json; every instance is tried in parallel
    // with first-success semantics (see TidalApi.tryInstances), so failing or
    // unreachable instances add no latency to the winning request.
    // Note: only hosts that resolve in DNS are listed; the *.monochrome.tf
    // instances were retired with the move to monochrome.st (verified via NS
    // + three resolvers: NXDOMAIN for every subdomain).
    @Provides
    @Singleton
    @Named("api.instances")
    fun provideApiInstances(): List<String> = listOf(
        "https://monochrome-api.samidy.com/",
        "https://wolf.qqdl.site/",
        "https://maus.qqdl.site/",
        "https://vogel.qqdl.site/",
        "https://hund.qqdl.site/"
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            android.util.Log.println(android.util.Log.DEBUG, "ChromePlayer-Http", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val userAgent = Interceptor { chain ->
            val request = chain.request()
            // Respect a User-Agent set explicitly by a client (e.g. SoundCloud's
            // desktop UA); only apply the default when none is present.
            if (request.header("User-Agent") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                        .build()
                )
            }
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
