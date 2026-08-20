# Changelog

All notable changes to Xmd are documented in this file.

## [Unreleased]

Changes staged for the next release. Update this section as you work; it's
what pre-release builds (`vX.Y.Z-beta.N`, `-rc.N`, etc.) pull their release
notes from until a matching `## [X.Y.Z]` heading exists below.

## [1.0.0] - 2026-08-20

First stable release.

### Added
- Paste `fuckingfast.co` share links, `dl.fuckingfast.co` direct links, or a `fitgirl-repacks.site` page URL to build a download queue.
- In-app WebView challenge screen to clear Cloudflare/Turnstile verification when a share link requires it.
- Resumable, pause/cancel-able downloads running in a foreground service.
- IDM-style auto-categorized downloads — files are sorted by extension into `Videos`, `Music`, `Documents`, `Apps`, or `Others` subfolders.
- Download queue persists across app restarts (Room-backed storage).
- New app icon and branding as **Xmd — Xtreme Media Downloader**.

### Fixed
- Adding a new link while another download was in progress no longer drops the in-flight item from the queue.

### Infrastructure
- CI builds a signed release APK via GitHub Actions (`apksigner`, secrets-based signing).
- Tagged releases (`vX.Y.Z`) publish a signed APK with checksums to GitHub Releases.
