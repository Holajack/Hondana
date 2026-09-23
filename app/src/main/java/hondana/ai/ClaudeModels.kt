package hondana.ai

/**
 * Claude models offered in settings. Prices are USD per million tokens
 * (input / output) and only feed the rough per-page estimate shown to the user.
 */
object ClaudeModels {

    data class Option(
        val id: String,
        val label: String,
        val inputPerMTok: Double,
        val outputPerMTok: Double,
    )

    const val DEFAULT = "claude-opus-5"

    val options = listOf(
        Option("claude-opus-5", "Claude Opus 5", 5.0, 25.0),
        Option("claude-sonnet-5", "Claude Sonnet 5", 2.0, 10.0),
        Option("claude-haiku-4-5", "Claude Haiku 4.5", 1.0, 5.0),
    )

    fun find(id: String): Option? = options.firstOrNull { it.id == id }

    /** Haiku 4.5 rejects the effort parameter; the Opus/Sonnet/Fable 4.6+ lines accept it. */
    fun supportsEffort(model: String): Boolean {
        return !model.startsWith("claude-haiku-") &&
            !model.startsWith("claude-3") &&
            !model.startsWith("claude-sonnet-4-5") &&
            !model.startsWith("claude-opus-4-5") &&
            !model.startsWith("claude-opus-4-1")
    }

    /**
     * Opus 5 and Fable 5.1 run safety classifiers that can decline a request.
     * For those, the request asks the API to retry a declined call on the
     * fallback model Anthropic recommends for that refusal category.
     */
    fun wantsServerFallback(model: String): Boolean {
        return model == "claude-opus-5" || model == "claude-fable-5-1"
    }

    /**
     * Rough cost of reading one screen: ~1.6k image tokens + ~0.9k prompt tokens in,
     * ~1.2k tokens out (bubbles, translations and some thinking).
     */
    fun estimatedCostPerPage(model: String): Double? {
        val option = find(model) ?: return null
        return (2_500 * option.inputPerMTok + 1_200 * option.outputPerMTok) / 1_000_000.0
    }
}
