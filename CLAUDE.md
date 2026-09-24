# Hondana: notes for AI agents

Hondana is a personal fork of Komikku (itself Mihon + TachiyomiSY). Read
`AGENTS.md` first for the Komikku architecture: modules, DI, Voyager screens,
SQLDelight, fork markers. This file covers only what Hondana adds.

## Where Hondana code lives

- `app/src/main/java/hondana/`: all new code.
  - `core/`: `Hondana` (lazy service locator), `HondanaPreferences`,
    `HondanaDatabase` (own SQLite file `hondana.db`), `Languages`.
  - `ai/`: Claude via the official Anthropic Java SDK (`ClaudeService`), prompts
    and JSON schemas (`ClaudePrompts`), result models. Pure JVM, no Android
    imports, so it can be compile-checked without the Android SDK.
  - `text/`: ML Kit OCR, reading-order heuristics, `PageReader` (engine choice
    and cache).
  - `translate/`, `speech/` (TTS `Speaker`, per-character `VoiceCast`),
    `vocab/` (saved words, Anki and dictionary hand-offs).
  - `reader/`: `HondanaReader` (owned by `ReaderActivity`),
    `AutoScrollController`, `ReaderAssistant` (lens + read aloud),
    `ScreenCapture`, and Compose UI in `reader/ui/`.
  - `settings/`: Settings → Reading assistant.
  - `extensions/`: `ExtensionSetup` repairs repos at launch (adds Keiyoushi's
    `index.pb`, upgrades legacy entries, loads repo-vouched extensions) and
    installs the extensions a restored library needs.
  - `failover/`: switching sources when a site is down. `TitleMatch` (pure
    Kotlin same-series test), `SourceHealth` (down site vs offline phone),
    `SourceFailover` (search candidates, verify the chapter, migrate with
    Komikku's `MigrateMangaUseCase`), `ChapterLoadErrors` (reported by
    `ReaderViewModel`). The reader side is `hondana/reader/ReaderFailover` and
    `reader/ui/FailoverCard`.
  - `safety/`: the always-on sexual-content filter. `AdultContentRules` (pure
    Kotlin word lists), `AdultContentFilter` (decisions, remembered blocked
    packages/source IDs), `AdultContentGuard` (startup: library cleanup,
    MangaDex rating, E-Hentai off, NSFW preference kept on),
    `AdultContentBlockedScreen`, `AdultSourceSettings` (strips adult switches
    and choices from extensions' settings). The on-device nudity check: `NudityDetector`
    (LiteRT + `assets/hondana/nsfw_mobilenet_v2.tflite`, thresholds),
    `NudityTiles` and `NudityResample` (pure Kotlin), `NudityScreen` (reader
    pages, the "hidden" card) and `NudityImageInterceptor` (Coil).
- `i18n-hondana/`: Hondana strings (moko-resources, class `hondana.i18n.HMR`).
  Edit only `base/strings.xml`.
- Edits to upstream files are wrapped in `// HONDANA -->` … `// HONDANA <--`.
  Today that is `ReaderActivity`, `ReaderAppBars`, `SettingsMainScreen`,
  `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `app/proguard-rules.pro`, `.gitignore`, and the launcher/splash drawables.
  The updater hook is in `AppUpdateChecker`. The content filter hooks into `App`, `ExtensionLoader`, `ExtensionManager`,
  `AndroidSourceManager`, `Extension` (domain), `NetworkExtensionStore`,
  `NetworkLegacyExtension`, `BackupRestorer`, `MangaScreen`, `MangaScreenModel`,
  `BrowseSourceScreenModel`, `SearchScreenModel`, `SourceFeedScreenModel`,
  `FeedScreenModel`, `RecommendsScreenModel`, `RecommendationSearchHelper`,
  `SettingsAdvancedScreen`, `SettingsBrowseScreen`, `SourcesTab`,
  `ExtensionsTab`, `SourcePreferencesScreen`, `AnilistApi` and
  `MyAnimeListApi`; the nudity check into
  `PagerPageHolder`, `WebtoonPageHolder`, `ReaderViewModel` and `App`'s image
  loader. Source switching hooks into `ReaderActivity` (opening error) and
  `ReaderViewModel` (`loadAdjacent`, `preload`).

## Rules

1. Put new features in `hondana` packages. Touch upstream files only for a hook,
   inside `HONDANA` markers, and keep the hook small.
2. Never change Komikku's SQLDelight schema. Hondana data goes in
   `HondanaDatabase`.
3. New strings go in `i18n-hondana` (`HMR`), never in `i18n`, `i18n-kmk` or
   `i18n-sy`.
4. Preferences go in `HondanaPreferences`. Secrets use `Preference.privateKey`
   so they stay out of backups.
5. Claude calls go through `ClaudeService` (Anthropic Java SDK). Default model
   `claude-opus-5`; the user picks others in settings.
6. The content filter (`hondana/safety`) has no off switch, by the owner's
   choice. Don't add one, and don't bring back Komikku's NSFW switches. It
   blocks sexual content and nudity only, not violence or gore.

## Building

GitHub Actions (`.github/workflows/build.yml`) builds `assembleRelease` on every
push to `main` (with `-Penable-updater`), checks the APK is signed with the
release key, and publishes it as a release tagged `r<commit count>` in this
(public) repo, keeping the ten newest. The in-app updater (`AppUpdateChecker`,
`HONDANA_RELEASES_REPO`) compares that `r` number with `BuildConfig.COMMIT_COUNT`.

The release key is committed only as `signing/hondana-release.tar.gpg`
(AES-256, passphrase in the `SIGNING_PASSPHRASE` secret); CI decrypts it before
building. Never commit `signing/*.jks` or `signing/signing.properties`. The key
committed before September 2026 was exposed when the repo went public and is
retired.

Locally (needs JDK 21 and Android SDK 36):

```bash
./gradlew assembleRelease          # debug-signed unless signing/ holds the decrypted key
./gradlew spotlessApply            # formatting (ktlint), optional
```

When the Android SDK or Google Maven is unreachable, as in some sandboxes,
you can still compile-check `hondana/ai/` and the pure-Kotlin parts of
`hondana/text/` in a throwaway Kotlin/JVM Gradle project that depends on
`com.anthropic:anthropic-java` and `kotlinx-serialization-json`.

## Checking a change on a phone

Install the APK from the release, then:

1. Reader in long-strip mode → Auto-scroll: scrolls smoothly; −/+ change speed;
   tapping the page opens the menu and pauses.
2. Paged mode → Auto-scroll: page turns every N seconds.
3. Lens without a Claude key: on-device text boxes appear on a Latin or
   Japanese page; tapping one opens its card; Translate downloads the ML Kit
   model once.
4. Lens with a key: speakers, translations and readings appear; Explain
   returns a word list.
5. Read aloud: lines are read in order with an outline, and the page turns at
   the end.
6. Settings → Reading assistant → Check connection.

## Syncing with Komikku

```bash
scripts/sync-komikku.sh             # merges upstream/master (or a tag), drops Komikku's workflows
```

Resolve conflicts around the `HONDANA` markers, then push. CI builds the APK.
Upstream workflow files must stay deleted: they need Komikku's secrets.
