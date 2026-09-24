package hondana.failover

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether two listings are the same series, strictly enough to move a library entry
 * without asking. Sites spell the same title slightly differently ("The Beginning After the End",
 * "Beginning After The End [Official]"), while sequels and spin-offs differ by a few words
 * ("Solo Leveling: Ragnarok"), so titles are normalized and must be nearly identical. Pure
 * Kotlin, so it can be checked on the JVM.
 */
object TitleMatch {

    /** Normalized titles at least this similar are the same series. */
    const val SAME_SERIES = 0.9

    private val articles = setOf("the", "a", "an")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val bracketed = Regex("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}|【[^】]*】")
    private val apostrophes = Regex("['’‘`´]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    // "Alternative: A ; B", "Associated Names: A, B", "Other names: A / B" in a description.
    private val alternativeLine = Regex(
        "^\\s*(?:alternative(?:\\s+(?:names?|titles?))?|alt(?:ernate)?\\.?\\s+(?:names?|titles?)|" +
            "associated\\s+names?|other\\s+names?|also\\s+known\\s+as)\\s*[:：]\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val strongSeparators = Regex("\\s*[;/|•、\\n]\\s*")

    fun normalize(title: String): String {
        val lower = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(combiningMarks, "")
            .replace(apostrophes, "")
        val words = words(lower.replace(bracketed, " ")).ifEmpty { words(lower) }
        return words.dropWhile { it in articles }.ifEmpty { words }.joinToString(" ")
    }

    private fun words(text: String): List<String> = text.split(nonWord).filter(String::isNotEmpty)

    // Words whose presence flips a title's meaning ("The Player Who Can't Level Up").
    private val negations = setOf("not", "no", "never", "cant", "cannot", "dont", "doesnt", "didnt", "isnt", "wont", "arent")

    /**
     * 1.0 for the same normalized title, down to 0.0 for nothing in common. Titles that differ in
     * a number ("Jujutsu Kaisen 0") or a negation are different series whatever the spelling says.
     */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val wordsX = x.split(' ')
        val wordsY = y.split(' ')
        if (wordsX.filter(::isNumber).sorted() != wordsY.filter(::isNumber).sorted()) return 0.0
        if (wordsX.any { it in negations } != wordsY.any { it in negations }) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
    }

    private fun isNumber(word: String) = word.all(Char::isDigit)

    /** The best similarity between [candidate] and any of [titles]. */
    fun bestSimilarity(titles: Collection<String>, candidate: String): Double =
        titles.maxOfOrNull { similarity(it, candidate) } ?: 0.0

    fun isSameSeries(titles: Collection<String>, candidate: String): Boolean =
        bestSimilarity(titles, candidate) >= SAME_SERIES

    /** Other names a site lists in the description, such as "Alternative: 나 혼자만 레벨업 ; Only I Level Up". */
    fun alternativeTitles(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        return alternativeLine.findAll(description)
            .flatMap { match ->
                val list = match.groupValues[1]
                val parts = list.split(strongSeparators)
                (if (parts.size > 1) parts else list.split(',')).asSequence()
            }
            .map { it.trim().trim('.', ',', '"') }
            .filter { it.length >= 2 }
            .distinct()
            .take(5)
            .toList()
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(substitution, min(previous[j] + 1, current[j - 1] + 1))
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
