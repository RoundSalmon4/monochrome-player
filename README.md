# ChromePlayer

A Kotlin + Jetpack Compose TIDAL music player for Android. Stream high-quality audio from TIDAL through a native, privacy-respecting interface.

## Features

| Feature | Description |
|---------|-------------|
| Search | Search TIDAL's catalog for artists, albums, and tracks |
| Album Detail | Browse album track listings with metadata and cover art |
| Artist Detail | View artist profiles and their discography |
| High-Res Playback | DASH and HLS streaming via Media3/ExoPlayer |
| Background Play | Persistent notification with media controls, continues playing when app is backgrounded |
| Mini Player | Compact now-playing bar with play/pause, skip, and progress |
| Queue | Dynamic track queue with next/previous navigation |
| Speed Control | Adjust playback speed from 0.25x to 3.0x |
| Local Playlists | Create and manage playlists stored locally in Room |
| Listen History | Locally-saved listening history |
| Artist Subscriptions | Follow artists and view them in your library |
| Picture-in-Picture | PiP mode when leaving the player |
| Import/Export | Backup and restore settings, playlists, and subscriptions |
| Customization | Theme colors, AMOLED dark mode, and dynamic color on Android 12+ |

## Architecture

Built from the ground up in Kotlin with Jetpack Compose:

- **UI**: Jetpack Compose with Material3, type-safe navigation
- **DI**: Hilt
- **Database**: Room (listen history, playlists, artist subscriptions)
- **Preferences**: DataStore
- **Playback**: Media3 ExoPlayer with DASH/HLS support
- **Background**: MediaSessionService with system media controls
- **API**: Retrofit + OkHttp to community TIDAL API instances

## Building

```bash
./gradlew assembleDebug
```

Debug APK is output to `app/build/outputs/apk/debug/`.

## License

MIT
