# Neifur 365 — Project Guidelines

## Communication
- Always respond, explain decisions, and summarize results in **Russian**.
- Explain trade-offs in plain user-facing terms, proactively critique weak UX/architecture (including existing code), and propose cleaner alternatives before building large features.

## Design & Code Quality
- **Design matters first**: every screen and control must feel intentional, cohesive, and uncluttered — matching `Neifur 365`'s dark aesthetic (`Nf.Ink`, `Nf.Surface`, `Nf.SurfaceHi`, `Nf.Line`, `Nf.Violet`, `Nf.Mint`, `Nf.Amber`, `Type`, `pressable`, smooth Compose animations).
- Keep the Kotlin + Jetpack Compose + `libmpv` codebase compact, readable, and free of boilerplate or over-abstraction.
- Anime 365 streams generally do not carry MKV chapters (unlike torrent sources); do not assume chapter markers exist.
- Never read/expose keystore secrets or commit `local.properties`.

## Delivery Workflow (Default After Code Changes)
1. Add or update unit tests in `app/src/test/java/app/neifur/` when changing pure logic, and run `.\gradlew.bat testDebugUnitTest`.
2. Verify the release build compiles cleanly with R8 and lint: `.\gradlew.bat assembleRelease` (zero errors/warnings).
3. If an Android device is connected, install the built release APK immediately via `<sdk.dir>/platform-tools/adb.exe install -r app/build/outputs/apk/release/app-release.apk` so the owner can test on their phone right away.
4. Commit the verified changes automatically with a concise English commit message matching the existing `git log` style (enabling easy rollback).
