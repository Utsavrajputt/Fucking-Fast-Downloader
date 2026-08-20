# Contributing to Xmd

Thanks for taking the time to contribute! This is a small project, so the process is kept lightweight.

## Getting set up

- JDK 17 and the Android SDK (compileSdk 34, minSdk 26).
- Open the project in Android Studio, or build from the command line:

  ```bash
  ./gradlew assembleDebug
  ```

See [README.md](README.md) for the full project structure and build/signing instructions.

## Before opening a PR

- Run lint locally so CI doesn't catch it first:

  ```bash
  ./gradlew lintRelease
  ```

- Keep changes focused — one logical change per PR is easier to review than several unrelated ones bundled together.
- If you're touching `QueueRepository.kt`, remember `setLinks()` must **merge** into the existing queue rather than replace it, or in-progress downloads get dropped from the UI (this bit us once already).
- Update `CHANGELOG.md` under an `## [Unreleased]` section if your change is user-facing (new feature, fix, behavior change). Maintainers will fold it into the next version section at release time.

## Commit messages

Short, descriptive, imperative mood is fine — e.g. `Fix category folder not created on first download`. No strict format enforced.

## Reporting bugs / requesting features

Use the issue templates — they ask for the info that's usually needed to act on a report (repro steps, device/version for bugs; problem being solved for features).

## Code style

- Kotlin, following the project's existing conventions (see any file under `app/src/main/java/com/invictus/xmd/` for reference).
- Prefer small, well-named functions over large ones; the existing `core/` classes (`LinkParser`, `CategoryDetector`, `DownloadEngine`) are good examples of the granularity we aim for.

## License

By contributing, you agree your contributions are licensed under the project's [AGPL-3.0 license](LICENSE).
