# Hondana

A personal manga reader for Android, built on
[Komikku](https://github.com/komikku-app/komikku) (itself a fork of
[Mihon](https://github.com/mihonapp/mihon) and TachiyomiSY). The name is 本棚,
"bookshelf".

Hondana keeps everything Komikku does: extension repositories, sources,
library, categories, trackers, downloads, backups and sync. On top of that it
adds reading and language-learning tools. See [Features](#features).
Komikku's own feature list is in [docs/KOMIKKU_README.md](docs/KOMIKKU_README.md).

## Install

**[Download hondana.apk](https://github.com/Holajack/hondana/releases/download/hondana-latest/hondana.apk)**

That link always serves the newest build, because every push to `main`
replaces it. The repo is private, so sign in to GitHub in your phone's browser
first. Then open the link and tap the downloaded file. The first time, Android
asks you to allow installs from that browser.

- Requires Android 8.0 (API 26) or newer.
- Installs **next to** Mihon or Komikku (package `com.holajack.hondana`), so you
  can try it without touching your current app.
- Every build is signed with the same key, so a new build installs over the
  old one and keeps your library.

### Bring your existing setup over

1. In Mihon or Komikku: **More → Backup and restore → Create backup**. Tick
   everything, including *Extension repos* and *App settings*.
2. In Hondana: **More → Backup and restore → Restore backup** and pick that
   file.

That restores your library, categories, reading history, trackers and
extension repos. Extensions are separate apps, so reinstall the ones you use
from **Browse → Extensions** once the repos are back. `tachiyomi://add-repo`
and `mihon://extension-store` links also open in Hondana.

## Features

Everything from Komikku. Hondana's own additions are listed in
[docs/FEATURES.md](docs/FEATURES.md) as they land.

## Building

GitHub Actions builds every push (`.github/workflows/build.yml`), publishes the
APK to the `hondana-latest` release, and keeps it as a workflow artifact for 30
days.

To build locally you need JDK 21 and the Android SDK (platform 36):

```bash
./gradlew assembleRelease      # signed APKs in app/build/outputs/apk/release/
./gradlew assembleDebug        # side-by-side dev build, package suffix .dev
```

## Keeping up with Komikku

`main` is Komikku's full history plus Hondana commits on top, so upstream
merges have a real merge base:

```bash
git remote add upstream https://github.com/komikku-app/komikku   # once
git fetch upstream
git merge upstream/master      # resolve, push; CI builds the new APK
```

Hondana code sits in `hondana` packages or between `// HONDANA -->` and
`// HONDANA <--` markers in upstream files. That keeps conflicts small and easy
to find. Syncing every few upstream releases is plenty.

## License

Apache License 2.0, same as Komikku and Mihon. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Hondana is not affiliated with Mihon, Komikku or Tachiyomi.
It ships no content and no extension repositories. You choose your own
sources.
