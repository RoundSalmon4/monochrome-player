package com.roundsalmon4.monochrome.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onBackClick: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credits & Licenses") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            item {
                CreditsItem(
                    dependencyName = "PhoneTube",
                    dependencyPackageName = "RoundSalmon4/PhoneTube (SmartTube lineage) - ChromePlayer's original code lineage; the playback service and PiP logic follow PhoneTube's MIT implementation",
                    dependencyLicense = MIT_LICENSE,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Monochrome",
                    dependencyPackageName = "monochrome-music/monochrome - streaming API conventions and the community instance pool ChromePlayer builds on",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Meld (fork of Metrolist)",
                    dependencyPackageName = "FrancescoGrazioso/Meld - multi-backend Qobuz resolver architecture (backend rotation, host/captcha cooldowns, quality ladder) ported into the Qobuz playback client",
                    dependencyLicense = GPL_3_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Stash",
                    dependencyPackageName = "rawnaldclark/Stash - reference for the JioSaavn DES-decrypted media template and the conservative track matcher used by the JioSaavn fallback",
                    dependencyLicense = GPL_3_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "AppVerifierBG",
                    dependencyPackageName = "RoundSalmon4/AppVerifierBG - pattern for ChromePlayer's Credits & Licenses screen (same author)",
                    dependencyLicense = MIT_LICENSE,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Media3 / ExoPlayer",
                    dependencyPackageName = "androidx.media3:media3-exoplayer - audio playback engine",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Retrofit",
                    dependencyPackageName = "com.squareup.retrofit2:retrofit - HTTPS networking",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "OkHttp",
                    dependencyPackageName = "com.squareup.okhttp3:okhttp - HTTP client",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Gson",
                    dependencyPackageName = "com.google.code.gson:gson - JSON parsing",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Hilt / Dagger",
                    dependencyPackageName = "com.google.dagger:hilt-android - dependency injection",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Room",
                    dependencyPackageName = "androidx.room:room-runtime - local database",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "DataStore",
                    dependencyPackageName = "androidx.datastore:datastore-preferences - preferences storage",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Coil",
                    dependencyPackageName = "io.coil-kt:coil-compose - image loading",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Kotlin Coroutines",
                    dependencyPackageName = "org.jetbrains.kotlinx:kotlinx-coroutines - concurrency",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Jetpack Compose",
                    dependencyPackageName = "androidx.compose - Material3 UI toolkit",
                    dependencyLicense = APACHE_2_0,
                )
            }
            item {
                CreditsItem(
                    dependencyName = "Community instances & relays",
                    dependencyPackageName = "Community-hosted metadata mirrors and stream resolvers ChromePlayer may rely on. They can change or go offline at any time.",
                    dependencyLicense = NOT_AFFILIATED,
                )
            }
        }
    }
}

@Composable
fun CreditsItem(
    dependencyName: String,
    dependencyPackageName: String,
    dependencyLicense: String,
) {
    var dropped by rememberSaveable { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable(
            onClickLabel = "View ${dependencyName}'s license",
            role = Role.DropdownList,
            onClick = { dropped = !dropped },
        ),
        headlineContent = { Text(text = dependencyName) },
        supportingContent = { Text(text = dependencyPackageName) },
        trailingContent = {
            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
        }
    )
    if (dropped) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            text = dependencyLicense,
        )
    }
}