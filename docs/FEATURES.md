# Hondana features

What Hondana adds on top of Komikku, how to use it, and where it lives in the
code. Everything else (sources, extensions and repos, library, trackers,
downloads, backups, sync) is Komikku's. See [KOMIKKU_README.md](KOMIKKU_README.md).

## In the reader

Open any chapter and tap the middle of the screen. A row of Hondana tools
sits just above the bottom bar:

| Tool | What it does |
| --- | --- |
| **Auto-scroll** | Starts or stops hands-free scrolling. |
| **Lens** | Freezes the screen and makes every piece of text tappable. |
| **Read aloud** | Reads the page aloud, bubble by bubble, then turns the page. |
| **Voices** | Edits the voice, pitch and pace of each character in this series. |
| **Words** | Your saved words and lines. |

### Auto-scroll

- **Long strip / webtoon**: scrolls smoothly, frame by frame. While it runs, a
  small pill at the bottom has ⏸, − speed +, and ✕.
- **Paged modes (left-to-right, right-to-left, vertical)**: turns the page every
  few seconds. The pill's − / + change the seconds per page. The countdown
  restarts whenever the page changes, including when you turn it yourself.
- Pauses automatically while the menu or a dialog is open, while you touch the
  page, and while read-aloud is turning pages. With *Keep scrolling after
  touching the page* off, touching the page pauses until you press ▶.
- Keeps the screen on while running.
- Reading settings → *Smooth auto scroll* (TachiyomiSY's setting) switches
  long strips between smooth scrolling and a screen at a time.
- It replaces TachiyomiSY's timer loop, and the old EH utilities panel under the
  top bar drives the same engine.

Code: `hondana/reader/AutoScrollController.kt`, `hondana/reader/ui/AutoScrollControls.kt`.

### Lens (translate, explain, look up, save)

Tap **Lens**. Hondana captures the screen and reads its text:

- **With Claude** (when an API key is set): every bubble, caption, sign and
  sound effect, in reading order, with who says it, a translation, and a reading
  aid (kana for Japanese, pinyin for Chinese, romanization for Korean).
- **On-device** (offline, free): Google ML Kit text recognition for Latin,
  Japanese, Chinese and Korean scripts. No speakers. Translations come from ML
  Kit's offline models, which download once per language pair (~30 MB).

Then:

- Tap an outlined box to open its card: the text (selectable), reading,
  translation, and actions. *Listen* speaks it; *Translate* translates it if
  needed; *Explain* asks Claude for a word-by-word breakdown with readings,
  dictionary forms, parts of speech, grammar and nuance. Each word has **+** to
  save it, and tapping a word looks it up. *Look up* sends the text to any
  installed dictionary app (Takoboto, Akebi, Jisho apps, Google Translate,
  DeepL…) or a web dictionary. *Copy*, *Save* and *Anki* (opens AnkiDroid's
  add-card screen pre-filled) round it out.
- The top bar's 文A button replaces every bubble with its translation, so you
  can flip the whole page between original and translation. 🔊 reads the whole
  screen. ⋮ re-reads the screen with the other engine.
- Back closes the card, then the lens.

Claude's page readings are cached by page image, so opening the lens again on
the same page costs nothing. *Settings → Reading assistant → Clear saved page
readings* empties the cache.

Code: `hondana/reader/ReaderAssistant.kt`, `hondana/reader/ui/LensOverlay.kt`,
`hondana/text/`, `hondana/translate/`, `hondana/ai/`.

### Read aloud with a voice for each character

Tap **Read aloud**. Hondana reads the live page in reading order, with an
outline around the bubble being read and the speaker's name at the top, and
then turns the page, or scrolls a long strip, and carries on. ⏸ pauses (and
repeats the line when resumed), ⏭ skips a line, ■ stops.

- When Claude reads the page it also names the speaker of each bubble and
  suggests a voice type (child, young, adult, elder; male or female; narrator).
  Each new character gets their own Android TTS voice plus a pitch and pace
  for that voice type. Assignments are saved per series and reused on every
  later page, and Claude is told the names it has already used so labels stay
  consistent.
- **Voices** lists the series' characters. Change the voice, pitch or pace, test
  it, or remove a character.
- *Settings → Reading assistant → Read* chooses between the original text
  (listening practice, spoken in the comic's language) and the translation
  (an audio drama in your language).
- On a long strip, a bubble cut off by the bottom of the screen is read after
  the next scroll, and lines already read are not repeated.
- Quality and the choice of voices depend on the phone's TTS engine. Google's
  engine has several voices for most languages; add more in Android's
  text-to-speech settings (link in Hondana's settings).

Code: `ReaderAssistant.readAloudLoop`, `hondana/speech/Speaker.kt`,
`hondana/speech/VoiceCast.kt`, `hondana/reader/ui/ReadAloudOverlay.kt`,
`hondana/reader/ui/VoiceCastDialog.kt`.

### Saved words

**Save** and **+** store words and lines with their reading, meaning, the
sentence, its translation, and where they came from (series · chapter). The
**Words** list, in the reader or under *Settings → Reading assistant → Saved
words*, lets you listen, mark words *New / Learning / Known*, send one to
AnkiDroid, delete, or share everything as TSV for Anki's desktop importer.
Fields: term, reading, meaning, sentence, sentence translation, source.

Code: `hondana/vocab/`, stored in `hondana.db` (see below).

## Content filter: no sexual content

Always on, with no switch to turn it off. Komikku's NSFW switches are gone too:
*Settings → Browse → NSFW content*, the "NSFW only" buttons in the Sources and
Extensions tabs, and the integrated E-Hentai switch. The filter targets sexual
content and nudity only: violence, gore, *Mature* and general sites that are
marked 18+ for other reasons stay available.

- **Adult-only extensions never load.** Repos rate every extension SAFE, MIXED
  or NSFW. NSFW means an adult-only site (hentai, doujin, porn, nude photos,
  adult manhwa). Those are hidden from the extension list, can't be installed,
  and don't load even if another app such as Mihon installed them on the phone.
  Keiyoushi rates 381 of its 1,397 extensions NSFW.
- **General sites stay.** MIXED sites (MangaDex, MangaFire, Weeb Central,
  Manganato and so on) carry the "18+" badge but stay available. Their adult
  sections are dropped: for example *Shadow Manga (+18)* and *MinoTruyen
  Hentai* are removed while the main sources remain.
- **Titles with sexual tags are hidden everywhere**: browsing, search, feeds,
  recommendations and related titles. A title whose genres include *Hentai,
  Smut, Ecchi, Fan service, Pornographic, Erotica, Adult, Sexual Violence, Loli,
  Shota, Uncensored* or similar, including MangaDex's *Content rating: Erotica /
  Pornographic*, is replaced by a "Blocked" screen as soon as its details load.
  It won't open in the reader and is deleted from the database, with its
  downloads.
- **Pages and covers are checked for nudity, on the phone.** Before a page is
  shown, Hondana looks at it with a small image model bundled in the app
  (GantMan's open NSFW model, MobileNet V2, 6.5 MB); nothing is uploaded. It
  checks the whole page and overlapping parts of it, so a figure in one panel
  counts too, and it checks webtoon strips panel by panel. A page that shows
  nudity is replaced by a grey "Page hidden" card and can't be saved, shared or
  set as a cover. Covers, page previews and other pictures get the same check
  and show a grey "Hidden" card instead.
- **MangaDex** is kept at its own *Safe + Suggestive* content rating, so its
  listings never include erotica or pornographic titles.
- **Backups and your existing library**: restoring a backup skips titles from
  adult-only sources and titles with sexual tags. Anything like that already
  in the library is removed at startup and whenever the library changes.
- **Also off**: TachiyomiSY's built-in E-Hentai/ExHentai features, and adult
  results in AniList and MyAnimeList tracker searches.

Repos without ratings fall back to site names (hentai, porn, 18+, and so on).

**Limits.** The image check is a statistical model, not a person. Its
thresholds were set so that ordinary pages aren't hidden: on 63 manga pages and
anime pictures and 40 webtoon-style strips with no nudity, it hid none. It
hasn't been measured on a collection of nude images, so nudity that is small,
partly covered or very stylised can still get through, and now and then an
innocent page may be hidden. Tell the maintainer about a source, tag or page
that slips through.

Code: `hondana/safety/`. The rules (`AdultContentRules`) are plain Kotlin and
were checked against Keiyoushi's full index: they flag none of its 579 SAFE
extensions. The image check is `NudityDetector` (model and thresholds),
`NudityTiles` (where it looks), `NudityResample` (Lanczos shrinking; plain
bilinear shrinking turns screentones into noise the model mistakes for skin),
`NudityScreen` (reader pages) and `NudityImageInterceptor` (everything loaded
through Coil).

## Settings → Reading assistant

- **Languages**: the language you're reading, and your own language.
- **Claude**: API key, model, effort, a connection check, and a per-page cost
  estimate.
  - The default model is Claude Opus 5, the most capable for reading pages.
    Sonnet 5 and Haiku 4.5 are cheaper. A page costs about $0.04 with Opus 5,
    $0.017 with Sonnet 5 and $0.009 with Haiku 4.5. These are estimates; Claude
    bills by tokens.
  - Get a key at console.anthropic.com → API keys. It is stored on the phone
    only, is left out of Komikku backups, and is sent only to Anthropic's API.
    Page images are sent to Anthropic only when Claude reads a page.
  - Requests to Opus 5 opt into server-side fallbacks, so a request its safety
    classifier declines is retried on the model Anthropic recommends instead of
    failing.
- **Text and translation**: read text with Claude or on-device, translate with
  Claude or on-device. The default (*Auto*) uses Claude when a key is set.
- **Read aloud**: original or translation, speech rate, per-character voices,
  automatic page turns, sound effects on or off.
- **Auto-scroll**: long-strip speed, and whether touching the page pauses it.
- **Sources → Install extensions for your library**: finds titles whose
  source isn't installed (for example after restoring a Mihon backup) and
  installs those extensions from your repos, one after another.
- **Content filter**: a note on what the always-on filter blocks and checks.

At every launch Hondana also keeps its repos in step with Mihon: it adds the
Keiyoushi repo (`index.pb`) if no repo carries Keiyoushi's signing key, moves
legacy repo entries from older backups onto their current index, and loads
extensions another app installed once their repo vouches for them. Code:
`hondana/extensions/ExtensionSetup.kt`.

Code: `hondana/settings/SettingsHondanaScreen.kt`, `hondana/core/HondanaPreferences.kt`.

## Under the hood

- **Claude** is called through the official Anthropic Java SDK
  (`com.anthropic:anthropic-java`) with structured JSON outputs. See
  `hondana/ai/ClaudeService.kt` and the prompts in `hondana/ai/ClaudePrompts.kt`.
- **Screen capture** uses `PixelCopy` on the reader view with Hondana's own UI
  hidden for two frames, so whatever the reader shows (paged, strip, zoomed,
  cropped, filtered) is exactly what gets read.
- **Storage**: Hondana keeps its own SQLite file, `hondana.db` (saved words,
  character voices, cached page readings), separate from Komikku's database so
  upstream schema migrations never collide. It is not part of Komikku backups.
- **Strings** live in their own moko-resources module, `i18n-hondana` (`HMR`).

## Rebrand and install

- Package `com.holajack.hondana`, so it installs next to Mihon or Komikku.
- Named "Hondana", with its own icon: 本 ("book") in a speech bubble.
- Stable release signing (see `signing/README.md`), so each update installs
  over the last.
- A GitHub Actions build that publishes every build as a numbered release with
  a stable download link, and an in-app updater that offers new builds.
