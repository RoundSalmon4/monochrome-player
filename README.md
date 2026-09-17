# ChromePlayer

A Kotlin + Jetpack Compose streaming music player for Android. It aggregates a
privacy-respecting chain of community sources to resolve TIDAL-based metadata
into playback, with lossless (FLAC/Hi-Res) preferred and lossy fallbacks kept
under a unified time budget so playback is interrupted as rarely as possible.

## Status

ChromePlayer is a community client that depends on third-party backends, which
can go down, get rate-limited, or be taken offline without notice. In that
spirit:

- **Upstream note:** the upstream Monochrome project received a cease-and-desist
  and removed Amazon Music DRM decryption from its code. ChromePlayer mirrors
  that alignment (its unified client now only accepts `legacy`/`tidal`/`mono`/
  `monochrome` sources and no longer attempts server-side decrypt), and the old
  `music-api.geeked.wtf` backend no longer resolves.
- Streaming falls back source-by-source; when none answer you get a clear error
  naming which sources were tried.

## Features

| Feature | Description |
|---------|-------------|
| Home feed | Live trending albums from the community hot feed, pull-to-refresh, reload on tab switch |
| Search | TIDAL-metadata search (tracks / artists / albums) across the community instance pool, load-more |
| Album / Artist pages | Track listings with metadata and cover art; Play All and Shuffle |
| Playlists | Local playlists stored in Room; detail view with Play All / Shuffle |
| Playback chain | Monochrome Playback -> SoundCloud -> Qobuz (multi-backend) -> Deezer -> Internet Archive, each with per-source diagnostics |
| Quality | FLAC/Hi-Res where the source offers it; lossy AAC/MP3 fallbacks otherwise |
| Waveform seekbar | Rich seek bar rendered from waveform data when available |
| Queue controls | Shuffle, repeat (off / all / one), smart previous, position save & resume, volume slider, sleep timer, speed 0.25x-3.0x |
| Queue persistence | Queue and mini-player state restored on relaunch |
| Background playback | Media3/ExoPlayer with MediaSession, persistent notification, works headless without crashing on throttled devices |
| Library | Local listen history, artist subscriptions, local playlists |
| Settings | Theme (Material 3 / dynamic color / AMOLED), clear cache, sleep timer, playback source options |
| Import/Export | Backup and restore settings, playlists, and subscriptions |
| Privacy | No account, no analytics, no telemetry |

## Streaming sources

ChromePlayer resolves a track through its metadata (community TIDAL mirrors)
then walks this chain until one returns a playable URL:

1. **Monochrome Playback** (`track-api.monochrome.tf`) — legacy lossless source;
   auto-refreshing Turnstile session via a hidden WebView.
2. **SoundCloud** (`SoundCloudClient`) — free catalog, matched by title/artist.
3. **Qobuz** (`QobuzProxyClient`) — lossless relay resolvers (Monokenny, Squid,
   TrypT, Jumo) with per-host cooldowns, captcha lockouts, and a
   Hi-Res -> CD -> AAC 320 quality ladder; ISRC-first matching. The community
   resolvers are frequently offline; instances are skipped quickly and retried
   on a cooldown.
4. **Deezer** (`DeezerProxyClient`) — community proxy, ISRC lookup.
5. **Internet Archive** — free, no sign-up lossless FLAC fallback.
6. **JioSaavn** (`JioSaavnClient`) — free AAC 320 fallback; the 320 kbps
   link is delivered as a DES-encrypted template in the search response and
   decrypted locally. Conservative title/artist/duration matching gates the
   result.
7. **Amazon Music** — last resort stream lookup.

Dead hosts are remembered for a few minutes so a down proxy cannot stall the
chain. Each source surfaces which failures it hit, so an unplayable track names
the sources that were tried instead of failing silently.

## Architecture

Built from scratch in Kotlin with Jetpack Compose:

- **UI**: Jetpack Compose Material3, type-safe navigation
- **DI**: Hilt
- **Database**: Room (playlists, listen history, artist subscriptions)
- **Preferences**: DataStore
- **Playback**: Media3 ExoPlayer, custom OkHttp data source (browser User-Agent
  + Referer) to pass Cloudflare and CDN TLS checks
- **Background**: MediaSession foreground service, built safely in `onCreate`
- **API**: Retrofit + OkHttp against a pool of community TIDAL-metadata
  instances, tolerant of both the classic envelope and flat `data.items`
  response shapes

## Building

```bash
./gradlew assembleRelease
```

A **debug** APK is built automatically for every push that touches app code
(uploaded as the `ChromePlayer-debug` artifact). Run the **Build** workflow
manually to build a release APK instead. No Android SDK is required locally; CI
produces the installable artifact.

Install the APK and disable battery optimization for ChromePlayer (Settings ->
Apps -> ChromePlayer -> Battery -> Unrestricted) for reliable background
playback and fast stream start times.

## Disclaimer

This project is an independent, unofficial client. It is not affiliated with
or endorsed by TIDAL, Monochrome, Qobuz, SoundCloud, Deezer, or any other
service named here. All trademarks belong to their respective owners. Content
availability depends on community infrastructure that the project does not
operate, and third-party services may change or revoke access at any time.

## License

MIT