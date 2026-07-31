package com.roundsalmon4.monochrome.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "player_preferences"
)

private object Keys {
    val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    val RESUME_PLAYBACK = booleanPreferencesKey("resume_playback")
    val SHOW_MINI_PLAYER = booleanPreferencesKey("show_mini_player")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val USE_AMOLED_THEME = booleanPreferencesKey("use_amoled_theme")
    val PRIMARY_COLOR = intPreferencesKey("primary_color")
    val SECONDARY_COLOR = intPreferencesKey("secondary_color")
    val COLOR_SCHEME_MODE = stringPreferencesKey("color_scheme_mode")
    val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
    val AMAZON_JWT = stringPreferencesKey("amazon_jwt")
    val AMAZON_JWT_EXPIRY = stringPreferencesKey("amazon_jwt_expiry")
    val MONOCHROME_JWT = stringPreferencesKey("monochrome_jwt")
    val MONOCHROME_JWT_EXPIRY = stringPreferencesKey("monochrome_jwt_expiry")
    val MONOCHROME_PLAYBACK_ENABLED = booleanPreferencesKey("monochrome_playback_enabled")
}

data class PreferencesUiState(
    val playbackSpeed: Float = 1.0f,
    val defaultQuality: String = "AUTO",
    val resumePlayback: Boolean = true,
    val showMiniPlayer: Boolean = true,
    val themeMode: String = "SYSTEM",
    val useAmoledTheme: Boolean = false,
    val primaryColor: Int = 0xFFFF0000.toInt(),
    val secondaryColor: Int = 0xFF282828.toInt(),
    val colorSchemeMode: String = "STANDARD",
    val pipEnabled: Boolean = true
)

@Singleton
class PlayerPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val uiState: Flow<PreferencesUiState> = context.playerDataStore.data.map { prefs ->
        PreferencesUiState(
            playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            defaultQuality = prefs[Keys.DEFAULT_QUALITY] ?: "AUTO",
            resumePlayback = prefs[Keys.RESUME_PLAYBACK] ?: true,
            showMiniPlayer = prefs[Keys.SHOW_MINI_PLAYER] ?: true,
            themeMode = prefs[Keys.THEME_MODE] ?: "SYSTEM",
            useAmoledTheme = prefs[Keys.USE_AMOLED_THEME] ?: false,
            primaryColor = prefs[Keys.PRIMARY_COLOR] ?: 0xFFFF0000.toInt(),
            secondaryColor = prefs[Keys.SECONDARY_COLOR] ?: 0xFF282828.toInt(),
            colorSchemeMode = prefs[Keys.COLOR_SCHEME_MODE] ?: "STANDARD",
            pipEnabled = prefs[Keys.PIP_ENABLED] ?: true
        )
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.playerDataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    suspend fun setDefaultQuality(quality: String) {
        context.playerDataStore.edit { it[Keys.DEFAULT_QUALITY] = quality }
    }

    suspend fun setResumePlayback(enabled: Boolean) {
        context.playerDataStore.edit { it[Keys.RESUME_PLAYBACK] = enabled }
    }

    suspend fun setShowMiniPlayer(enabled: Boolean) {
        context.playerDataStore.edit { it[Keys.SHOW_MINI_PLAYER] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.playerDataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setUseAmoledTheme(enabled: Boolean) {
        context.playerDataStore.edit { it[Keys.USE_AMOLED_THEME] = enabled }
    }

    suspend fun setPrimaryColor(color: Int) {
        context.playerDataStore.edit { it[Keys.PRIMARY_COLOR] = color }
    }

    suspend fun setSecondaryColor(color: Int) {
        context.playerDataStore.edit { it[Keys.SECONDARY_COLOR] = color }
    }

    suspend fun setColorSchemeMode(mode: String) {
        context.playerDataStore.edit { it[Keys.COLOR_SCHEME_MODE] = mode }
    }

    suspend fun setPiPEnabled(enabled: Boolean) {
        context.playerDataStore.edit { it[Keys.PIP_ENABLED] = enabled }
    }

    suspend fun setAmazonJwt(jwt: String, expiryTimestamp: Long) {
        context.playerDataStore.edit {
            it[Keys.AMAZON_JWT] = jwt
            it[Keys.AMAZON_JWT_EXPIRY] = expiryTimestamp.toString()
        }
    }

    suspend fun getAmazonJwt(): Pair<String, Long>? {
        val prefs = context.playerDataStore.data.first()
        val jwt = prefs[Keys.AMAZON_JWT] ?: return null
        val expiry = prefs[Keys.AMAZON_JWT_EXPIRY]?.toLongOrNull() ?: 0L
        return Pair(jwt, expiry)
    }

    suspend fun setMonochromeJwt(jwt: String, expiryTimestamp: Long) {
        context.playerDataStore.edit {
            it[Keys.MONOCHROME_JWT] = jwt
            it[Keys.MONOCHROME_JWT_EXPIRY] = expiryTimestamp.toString()
        }
    }

    suspend fun getMonochromeJwt(): Pair<String, Long>? {
        val prefs = context.playerDataStore.data.first()
        val jwt = prefs[Keys.MONOCHROME_JWT] ?: return null
        val expiry = prefs[Keys.MONOCHROME_JWT_EXPIRY]?.toLongOrNull() ?: 0L
        return Pair(jwt, expiry)
    }

    suspend fun setMonochromePlaybackEnabled(enabled: Boolean) {
        context.playerDataStore.edit { it[Keys.MONOCHROME_PLAYBACK_ENABLED] = enabled }
    }

    suspend fun isMonochromePlaybackEnabled(): Boolean =
        context.playerDataStore.data.first()[Keys.MONOCHROME_PLAYBACK_ENABLED] ?: true
}
