# Xmd — Xtreme Media Downloader

> ⚠️ **Pre-release (`v1.0.0-beta.1`)** — under active development, expect rough edges. See [CHANGELOG.md](CHANGELOG.md) for what's new.

An Android download manager built for FuckingFast share links (`fuckingfast.co`), with fitgirl-repacks page support and Cloudflare/Turnstile challenge handling — an Android port of the original PyQt5 desktop downloader.

## Features

**Downloader**
- Paste `fuckingfast.co` share links, `dl.fuckingfast.co` direct links, or a `fitgirl-repacks.site` page URL — the app expands source pages into their share links automatically.
- If a link needs Cloudflare/Turnstile verification, an in-app WebView opens the share page so you can clear the challenge yourself; once cleared, the direct URL is captured automatically.
- Resumable, pause/cancel-able downloads that run in a foreground service, so they survive backgrounding the app.
- IDM-style **auto-categorized downloads** — files are sorted by extension into `Videos`, `Music`, `Documents`, `Apps`, or `Others` subfolders (or saved flat into Downloads via a Settings toggle).
- Auto-retry on network errors, an expired-link retry popup, and per-item/bulk retry & clear actions.
- Download queue persists across app restarts (Room-backed), with auto-resume of queued items.

**Browser**
- Built-in Browser tab with speed-dial bookmarks (real site favicons), address-bar search suggestions, tabs, and history.
- Automatic download interception for files opened in-app.
- Swipe gestures to switch tabs.
- Private DNS setting — AdGuard DNS-over-HTTPS (default), Off, or a Custom DoH endpoint.

## Project structure

```text
app/src/main/java/com/invictus/xmd/
├─ core/
│  ├─ LinkParser.kt              # share/direct/fitgirl link parsing & validation
│  ├─ DownloadEngine.kt          # resumable streaming download engine
│  ├─ CategoryDetector.kt        # extension -> DownloadCategory mapping
│  ├─ QueueRepository.kt         # in-memory + Room-backed queue state
│  ├─ Settings.kt                # persisted app settings (incl. Browser DNS mode)
│  ├─ DnsOverHttpsResolver.kt    # DoH resolver used by the in-app Browser
│  ├─ BookmarkRepository.kt, Bookmark.kt
│  ├─ HistoryRepository.kt, HistoryEntry.kt
│  ├─ FaviconLoader.kt, SuggestApi.kt
│  └─ db/                        # Room entities/DAOs (queue, bookmarks, history)
├─ service/
│  └─ DownloadService.kt         # foreground service driving downloads per category folder
├─ ui/
│  ├─ MainActivity.kt, HomeFragment.kt, DownloadsFragment.kt, QueueAdapter.kt
│  ├─ BrowserFragment.kt         # speed-dial, WebView, tabs, DNS settings
│  ├─ HistoryFragment.kt, HistoryAdapter.kt
│  ├─ BookmarkAdapter.kt, SuggestionAdapter.kt
│  └─ ChallengeActivity.kt       # WebView for clearing Cloudflare/Turnstile challenges
└─ FfApp.kt                      # Application class
```

## Building

Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 26).

```bash
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # unsigned release APK, then sign with apksigner (see below)
```

Or open the project in Android Studio and run/build normally.

### Signing a release build

Release builds are intentionally unsigned by Gradle — `assembleRelease` produces `app-release-unsigned.apk`, which you sign explicitly with `apksigner`:

```bash
apksigner sign --ks your-release.jks --ks-key-alias <alias> \
  --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

CI does this automatically on push to `main` via `.github/workflows/android-build.yml`, using repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); the signed APK is uploaded as a build artifact.

## Releases

Two tag-triggered workflows build a signed APK and publish it to GitHub Releases with a SHA-256 checksum and notes pulled from `CHANGELOG.md`:

- **`.github/workflows/release.yml`** — stable releases, triggered by tags matching `vX.Y.Z` (e.g. `v1.0.0`).
- **`.github/workflows/prerelease.yml`** — pre-releases, triggered by tags matching `vX.Y.Z-suffix` (e.g. `v1.0.0-beta.1`, `v1.0.0-rc.2`). Published GitHub Releases are flagged **Pre-release** automatically.

To cut a release:

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts` and add a matching `## [x.y.z]` (or `## [x.y.z-beta.N]`) section to the top of `CHANGELOG.md`, then commit and push those to `main`.
2. Tag the commit to match and push the tag:

   ```bash
   # stable
   git tag v1.0.0
   git push origin v1.0.0

   # pre-release
   git tag v1.0.0-beta.1
   git push origin v1.0.0-beta.1
   ```

3. The matching job runs automatically and publishes the GitHub Release with `Xmd-<tag>.apk` attached.

You can also trigger either workflow manually from the **Actions** tab → **Make release** / **Make pre-release** → **Run workflow**, entering the tag name without needing to push a tag first.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` — fetching links and downloading
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` — background download progress notification
- `MANAGE_EXTERNAL_STORAGE` — saving downloaded files into category subfolders

## License

Licensed under the GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).

Only download content you are authorized to access.
