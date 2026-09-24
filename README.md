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

**[Download hondana-arm64-v8a.apk](https://github.com/Holajack/Hondana/releases/latest/download/hondana-arm64-v8a.apk)**
(almost every phone from 2017 on). If it won't install, use the universal
[hondana.apk](https://github.com/Holajack/Hondana/releases/latest/download/hondana.apk),
which is larger.

These links are public (no GitHub sign-in needed), so they work for friends
too. Open one on the phone and tap the downloaded file. The first time, Android
asks you to allow installs from that browser. If Chrome stalls on the download,
use Firefox.

- Requires Android 8.0 (API 26) or newer.
- Installs **next to** Mihon or Komikku (package `com.holajack.hondana`), so you
  can try it without touching your current app.
- Every build is signed with the same key, so a new build installs over the
  old one and keeps your library.

### Updates

Hondana checks this repo's [releases](https://github.com/Holajack/Hondana/releases)
for new builds, like Mihon does: when one is out it shows what's new with a
**Download** button, and a notification when the app is closed. Check now under
**More → About → Check for updates**. On Android 12+ it downloads updates in the
background on Wi-Fi and, once Hondana installed the previous update itself,
installs them without asking.

Every push to `main` builds a new version (tagged `r<build number>`); the ten
newest stay on the releases page.

### Bring your existing setup over

1. In Mihon or Komikku: **More → Backup and restore → Create backup**. Tick
   everything, including *Extension repos* and *App settings*.
2. In Hondana: **More → Backup and restore → Restore backup** and pick that
   file.

That restores your library, categories, reading history, trackers and
extension repos. Hondana adds the Keiyoushi repo itself if it's missing, and
upgrades repo entries from older backups to Keiyoushi's current index (the old
one now lists only "Outdated App" placeholders).

Extensions that Mihon installed as regular apps load in Hondana automatically.
Ones Mihon installed privately (its *Private* installer) are invisible to other
apps: install them in Hondana with **Settings → Reading assistant → Install
extensions for your library**, or from **Browse → Extensions**.
`tachiyomi://add-repo` and `mihon://extension-store` links also open in Hondana.

## Features

Everything from Komikku, plus a row of reading tools in the reader menu:

- **Auto-scroll** for every reading mode: smooth, adjustable scrolling for
  long strips, and timed page turns for paged manga. Pauses when you touch the
  page or open the menu.
- **Lens**: freeze the screen and tap any bubble to translate it, hear it, get
  a word-by-word explanation with grammar notes, look it up in your dictionary
  app, save it, or send it to AnkiDroid. Flip the whole page between original
  and translation.
- **Read aloud**: hands-free reading, bubble by bubble, with a separate voice
  for each character, then on to the next page. Read the original for listening
  practice, or the translation as an audio drama.
- **Saved words** with New / Learning / Known status, AnkiDroid and TSV export.
- **No porn or hentai**, always: adult-only extensions never load, general
  sites stay but lose their adult sections, and titles tagged as sexual content
  are hidden and removed. Violence and gore aren't filtered. See
  [Content filter](docs/FEATURES.md#content-filter-no-porn-or-hentai).

Text recognition and translation work offline on the phone (Google ML Kit:
Latin, Japanese, Chinese and Korean scripts). Add a Claude API key under
**Settings → Reading assistant** for speakers, better translations and
explanations, and for other scripts. Details, costs and privacy notes are in
[docs/FEATURES.md](docs/FEATURES.md).

## Building

GitHub Actions builds every push (`.github/workflows/build.yml`), publishes it
as a release tagged `r<build number>`, and keeps the APKs as a workflow artifact
for 30 days. Official builds are signed with a key that is stored in this repo
only in encrypted form; see [signing/README.md](signing/README.md).

To build locally you need JDK 21 and the Android SDK (platform 36):

```bash
./gradlew assembleRelease      # APKs in app/build/outputs/apk/release/ (debug-signed without the key)
./gradlew assembleDebug        # side-by-side dev build, package suffix .dev
```

## Keeping up with Komikku

`main` is Komikku's full history plus Hondana commits on top, so upstream
merges have a real merge base:

```bash
scripts/sync-komikku.sh            # or: scripts/sync-komikku.sh v1.15.0
git push                           # CI builds and publishes the new APK
```

The script adds the `upstream` remote if needed, merges, and removes
Komikku's CI workflows again (they need Komikku's secrets). If there are
conflicts, it lists them for you to resolve.

Hondana code sits in `hondana` packages or between `// HONDANA -->` and
`// HONDANA <--` markers in upstream files. That keeps conflicts small and easy
to find. Syncing every few upstream releases is plenty.

## License

Apache License 2.0, same as Komikku and Mihon. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Hondana is not affiliated with Mihon, Komikku or Tachiyomi.
It ships no content and no extension repositories. You choose your own
sources.
