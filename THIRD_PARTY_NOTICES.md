# Third-Party Notices

ChromePlayer is licensed under the **GNU General Public License v3.0**
(see `LICENSE`). Third-party libraries and projects keep their own licenses
(all used here are GPL-compatible: Apache-2.0 and MIT).

## Directly-derived works (code/project lineage)

| Project | Repo | License | What ChromePlayer uses |
|---|---|---|---|
| SmartTube / PhoneTube lineage | github.com/yuliskov / github.com/RoundSalmon4/PhoneTube | MIT | Original code lineage; PlaybackService and picture-in-picture logic following PhoneTube's MIT implementation |
| Meld (fork of Metrolist) | github.com/FrancescoGrazioso/Meld | GPL-3.0 | Multi-backend Qobuz resolver architecture in `QobuzProxyClient.kt` (backend rotation, host/captcha cooldowns, quality ladder, ISRC matching) |
| Stash | github.com/rawnaldclark/Stash | GPL-3.0 | JioSaavn DES-decrypted media template and conservative track matcher in `JioSaavnClient.kt` |
| Monochrome | github.com/monochrome-music/monochrome | Apache-2.0 | Streaming API conventions and the community metadata/playback instance pool |
| AppVerifierBG | github.com/RoundSalmon4/AppVerifierBG | MIT (same author) | Pattern for the in-app Credits & Licenses screen |

### GPL-3.0 components

The following files are derived from GPL-3.0-licensed projects and are
distributed under the GNU General Public License v3.0:

- `app/src/main/java/com/roundsalmon4/monochrome/core/api/internal/QobuzProxyClient.kt`
- `app/src/main/java/com/roundsalmon4/monochrome/core/api/internal/JioSaavnClient.kt`

Full text of the GPL: https://www.gnu.org/licenses/gpl-3.0.txt

ChromePlayer as a whole is distributed under the GNU GPL v3.0, so these files
are covered by the app's own license.

## Third-party libraries (bundled via Gradle)

| Library | Coordinate | License |
|---|---|---|
| Media3 / ExoPlayer | androidx.media3:* | Apache-2.0 |
| Retrofit | com.squareup.retrofit2:* | Apache-2.0 |
| OkHttp | com.squareup.okhttp3:* | Apache-2.0 |
| Gson | com.google.code.gson:gson | Apache-2.0 |
| Hilt / Dagger | com.google.dagger:* | Apache-2.0 |
| Room | androidx.room:* | Apache-2.0 |
| DataStore | androidx.datastore:* | Apache-2.0 |
| Coil | io.coil-kt:* | Apache-2.0 |
| Kotlin Coroutines | org.jetbrains.kotlinx:kotlinx-coroutines-* | Apache-2.0 |
| Jetpack Compose / Material3 | androidx.compose:* | Apache-2.0 |

Each library's full license text is available from its own repository/Gradle
distribution; licenses for the AndroidX and Square libraries are Apache-2.0,
and the standard Apache-2.0 notice is reproduced at
https://www.apache.org/licenses/LICENSE-2.0.

## Community services

ChromePlayer may connect to community-hosted metadata mirrors and stream
resolvers. These are independent third-party services operated by community
members and are not part of ChromePlayer. They can change, rate-limit, or go
offline at any time. No affiliation, endorsement, or warranty is implied.