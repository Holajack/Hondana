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
- `i18n-hondana/`: Hondana strings (moko-resources, class `hondana.i18n.HMR`).
  Edit only `base/strings.xml`.
- Edits to upstream files are wrapped in `// HONDANA -->` … `// HONDANA <--`.
  Today that is `ReaderActivity`, `ReaderAppBars`, `SettingsMainScreen`,
  `app/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`,
  `app/proguard-rules.pro`, `.gitignore`, and the launcher/splash drawables.

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

## Building

GitHub Actions (`.github/workflows/build.yml`) builds `assembleRelease` on every
push to `main`. It publishes `hondana.apk` to the `hondana-latest` release and
uploads the APKs as a workflow artifact. A clean build takes about 30–40
minutes on the free 2-core runner, less with a warm Gradle cache.

Locally (needs JDK 21 and Android SDK 36):

```bash
./gradlew assembleRelease          # signed with signing/hondana-release.jks
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
git fetch upstream                  # upstream = https://github.com/komikku-app/komikku
git merge upstream/master
```

Resolve conflicts around the `HONDANA` markers. Upstream workflow files that
come back in a merge are deleted again: they need Komikku's secrets.
