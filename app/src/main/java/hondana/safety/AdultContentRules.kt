package hondana.safety

import java.text.Normalizer
import java.util.Locale

/**
 * Word lists and matching for Hondana's sexual-content filter. Pure Kotlin with no Android types,
 * so it can be tested on the JVM against real repo indexes. [AdultContentFilter] applies it.
 *
 * Sexual content is targeted: porn, hentai, and ecchi or fan-service titles, which are built
 * around nudity. Violence, gore, "Mature", "Seinen" and similar labels are left alone, and so
 * are general sites that merely host some adult titles.
 */
object AdultContentRules {

    /** `tachiyomix.contentWarning` in an extension manifest: 0 safe, 1 mixed, 2 adult-only (NSFW). */
    const val MANIFEST_CONTENT_WARNING_ADULT_ONLY = 2

    // Source and extension names. Long, unambiguous stems match anywhere in the squashed name
    // ("nhentai.xxx" -> "nhentaixxx"); short ones must be whole words.
    private val sourceStems = listOf(
        "hentai", "porn", "xxx", "nsfw", "smut", "lewd", "erotic", "erotik", "rule34", "booru", "e621",
        "yiff", "futanari", "lolicon", "shotacon", "fakku", "nhentai", "pururin", "tsumino", "8muses",
        "eightmuses", "hanime", "coomer", "manhwa18", "manga18", "18comic", "comic18", "toon18",
        "webtoon18", "adultwebtoon", "adultcomic", "sexcomic", "nudes",
    )
    private val sourceWords = setOf(
        "sex", "sexy", "ero", "adult", "adults", "nude", "naked", "milf", "r18", "hitomi", "luscious",
        "kemono", "doujin", "doujins", "doujinshi", "h", "18",
    )

    // Genre labels, compared whole. "Adult" is here but "Young Adult" is not, "Mature" is not.
    private val genreExact = setOf(
        "hentai", "smut", "adult", "adults", "adulto", "adultos", "adulte", "adult content", "adults only",
        "porn", "porno", "pornographic", "pornography", "pornografia", "pornografico", "pornographique",
        "erotica", "erotic", "erotico", "erotique", "erotik", "erotismo", "ero", "eroge",
        "sexual violence", "sexual content", "sex", "nudity", "nude", "uncensored",
        "lolicon", "loli", "shotacon", "shota", "futanari", "nsfw", "xxx",
        "18", "18+", "+18", "r18", "r 18", "r-18", "18 plus", "pornhwa", "hentai manhwa",
        "fan service", "fanservice", "echi", "nudez", "desnudos",
    )

    // Genre stems matched anywhere in a genre label ("Content rating: Pornographic", "Эротика").
    private val genreStems = listOf(
        "hentai", "porn", "erotic", "erotik", "smut", "lolicon", "shotacon", "sexual violence",
        "sexual content", "uncensored", "nsfw", "ecchi", "эрот", "порн", "хентай", "этти", "эччи",
    )

    // CJK and Korean markers, matched in the raw text (names, genres and titles).
    private val cjkMarkers = listOf("成人", "色情", "18禁", "エロ", "工口", "绅士", "紳士", "禁漫", "성인", "19금", "야동")

    // Extra markers for genre labels only: "ecchi" and Chinese "fan service".
    private val genreCjkMarkers = listOf("エッチ", "卖肉", "賣肉")

    // Title markers. "エロ" alone is left out of titles on purpose (Eromanga Sensei).
    private val titleWords = setOf("hentai", "uncensored", "porn", "porno", "nsfw", "xxx", "r18", "pornhwa")
    private val titleCjkMarkers = listOf("18禁", "成人向", "色情", "工口", "성인", "19금")

    /** True for the name of a site that is adult-only (porn, hentai, adult manhwa, nude photos). */
    fun isAdultSourceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val raw = name.lowercase(Locale.ROOT)
        if (cjkMarkers.any { it in raw }) return true
        if ("18+" in raw || "+18" in raw || "(18)" in raw || "[18]" in raw) return true
        val words = words(raw)
        if (words.any { it in sourceWords }) return true
        val squashed = words.joinToString("")
        return sourceStems.any { it in squashed }
    }

    /** True when a title's genres or title say it is sexual content. */
    fun isSexualTitle(title: String?, genres: Collection<String>?): Boolean {
        genres?.forEach { if (isSexualGenre(it)) return true }
        if (title.isNullOrBlank()) return false
        val raw = title.lowercase(Locale.ROOT)
        if (titleCjkMarkers.any { it in raw }) return true
        if ("18+" in raw || "+18" in raw || "[18]" in raw || "(18)" in raw || "r-18" in raw) return true
        return words(raw).any { it in titleWords }
    }

    fun isSexualGenre(genre: String): Boolean {
        val raw = genre.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        if (cjkMarkers.any { it in raw } || genreCjkMarkers.any { it in raw }) return true
        val plain = stripAccents(raw)
        if (plain in genreExact) return true
        val spaced = words(plain).joinToString(" ")
        if (spaced in genreExact) return true
        return genreStems.any { it in plain }
    }

    // Extensions' own settings ("Show NSFW content", a content rating list with "Erotica").
    // "Mature" is left out: it often means violence.
    private val settingWords = setOf(
        "nsfw", "hentai", "porn", "porno", "pornographic", "pornography", "erotic", "erotica", "ecchi",
        "smut", "lewd", "r18", "explicit", "sexual", "adult", "adults", "nude", "nudity", "uncensored", "sfw",
    )
    private val settingStems = listOf("nsfw", "hentai", "porn", "erotic", "ecchi", "smut", "lewd", "uncensored")
    private val hidingWords = setOf(
        "hide", "hidden", "exclude", "excluded", "block", "blocked", "blur", "blurred", "filter", "censor",
        "censored", "remove", "skip", "mask", "safe", "sfw",
    )
    private val negatingWords = setOf("no", "not", "don", "dont", "without", "disable", "disabled", "off")
    private val safeChoiceWords = setOf("safe", "sfw", "general", "everyone", "kids", "hide", "hidden", "exclude", "none", "no", "off")

    /** True for a source setting (a title, summary, key or list choice) about adult content. */
    fun isAdultSetting(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val raw = text.lowercase(Locale.ROOT).replace("young adult", "")
        if ("18+" in raw || "+18" in raw || "r-18" in raw || cjkMarkers.any { it in raw }) return true
        if (settingStems.any { it in raw }) return true
        return words(raw).any { it in settingWords }
    }

    /**
     * For a switch or list titled [title]: true when turning it on (or ticking a choice) keeps
     * adult content out, as in "Hide NSFW", "Blur explicit covers" or "Exclude genres".
     */
    fun onKeepsAdultOut(title: String): Boolean {
        val words = words(title.lowercase(Locale.ROOT))
        return words.any { it in hidingWords } != words.any { it in negatingWords }
    }

    /** A list choice such as "Safe", "SFW only" or "Hide". */
    fun isSafeChoice(label: String): Boolean = words(label.lowercase(Locale.ROOT)).any { it in safeChoiceWords }

    /** A list choice that lets adult content in, such as "Erotica" or "NSFW only" (not "SFW only"). */
    fun isAdultChoice(label: String?): Boolean = isAdultSetting(label) && !isSafeChoice(label.orEmpty())

    /** Lower-case words with accents removed; anything that isn't a letter or digit splits words. */
    private fun words(text: String): List<String> =
        stripAccents(text).split(nonWord).filter(String::isNotEmpty)

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "")

    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val combiningMarks = Regex("\\p{Mn}+")
}
