# 📜 Changelog

All notable changes to **Xmd** are documented in this file.
The format loosely follows [Keep a Changelog](https://keepachangelog.com/), and versioning follows [SemVer](https://semver.org/) with pre-release identifiers (`-beta.N`, `-rc.N`, ...) leading up to `1.0.0`.

## [Unreleased]

Changes staged for the next release. Update this section as you work; it's
what pre-release builds (`vX.Y.Z-beta.N`, `-rc.N`, etc.) pull their release
notes from until a matching `## [X.Y.Z]` heading exists below.

## [1.0.0-beta.1] - 2026-08-20

🚀 **First public pre-release** of Xmd — Xtreme Media Downloader.

### ✨ Added
- 📥 **Core downloader** — paste `fuckingfast.co` share links, `dl.fuckingfast.co` direct links, or a `fitgirl-repacks.site` page URL to build a download queue.
- 🛡️ In-app **WebView challenge screen** to clear Cloudflare/Turnstile verification when a share link requires it.
- ⏸️ **Resumable, pause/cancel-able downloads** running in a foreground service.
- 🗂️ **IDM-style auto-categorized downloads** — files sorted by extension into `Videos`, `Music`, `Documents`, `Apps`, or `Others` subfolders (with a Settings toggle to save flat into Downloads instead).
- 💾 Download queue **persists across app restarts** (Room-backed storage), with auto-resume of queued downloads and manual Start/Clear buttons.
- 🔁 **Auto-retry on network errors** (3 attempts, toggle in Settings).
- ⏰ IDM-style **expired-link popup** with one-tap retry, plus per-item and bulk retry-all/clear-all actions.
- 🔔 "Starting download" snackbar with a **VIEW** action.
- 🌐 New **Browser tab** — speed-dial bookmarks with real site favicons, in-app WebView browsing, and automatic download interception.
- 🔍 **Address bar** with DuckDuckGo search suggestions, tap-to-load, and quick-add bookmarks.
- 📜 **Browsing history** tab/overlay.
- ↔️ **Swipe gestures** to switch between bottom navigation tabs.
- 🔒 **Private DNS** setting for in-app browsing — AdGuard DNS-over-HTTPS (default), Off, or a Custom DoH endpoint.
- 🎨 **Rebranded** to Xmd — Xtreme Media Downloader, with a new adaptive app icon and package ID `com.invictus.xmd`.

### 🏗️ Infrastructure
- 🤖 CI builds a **signed release APK** via GitHub Actions (`apksigner`, secrets-based signing).
- 🏷️ Tagged stable releases (`vX.Y.Z`) publish a signed APK with a SHA-256 checksum to GitHub Releases.
- 🧪 Tagged **pre-releases** (`vX.Y.Z-beta.N`, `-rc.N`, ...) publish a signed, clearly-flagged pre-release build via a dedicated workflow.
- 🧹 CI lint checks, issue templates, and a CONTRIBUTING guide.

---
> ⚠️ **This is a pre-release build** — expect rough edges. Please [open an issue](../../issues) if you run into problems.
