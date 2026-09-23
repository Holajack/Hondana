package hondana.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The reading assistant's calls to Claude, through the official Anthropic Java SDK.
 *
 * All methods block; call them off the main thread. Failures surface as
 * [ClaudeException] with a message that can be shown to the user.
 */
class ClaudeService(private val settings: () -> Settings) {

    data class Settings(
        val apiKey: String,
        val model: String,
        /** low, medium or high. Ignored for models that don't take an effort level. */
        val effort: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    @Synchronized
    private fun client(apiKey: String): AnthropicClient {
        cachedClient?.takeIf { cachedKey == apiKey }?.let { return it }
        cachedClient?.close()
        // The SDK sizes its own timeout from max_tokens and retries 408/429/5xx/529 with backoff.
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(2)
            .build()
            .also {
                cachedKey = apiKey
                cachedClient = it
            }
    }

    fun isConfigured(): Boolean = settings().apiKey.isNotBlank()

    /** Checks the key and that the chosen model is available to it. Returns the model's display name. */
    fun checkConnection(): String {
        val s = requireSettings()
        return mapErrors(s.model) {
            client(s.apiKey).models().retrieve(s.model).displayName()
        }
    }

    /**
     * Reads every piece of text on one screen of a comic: transcription, speaker,
     * translation, reading aid and position, in reading order.
     */
    fun analyzePage(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        learningLanguage: String,
        nativeLanguage: String,
        layout: PageLayout,
        knownCharacters: List<PageCharacter>,
    ): PageAnalysisResult {
        val image = ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .data(Base64.getEncoder().encodeToString(jpeg))
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .build(),
                )
                .build(),
        )
        val instruction = ContentBlockParam.ofText(
            TextBlockParam.builder().text(ClaudePrompts.pageUser(width, height)).build(),
        )
        return callJson(
            system = ClaudePrompts.pageSystem(learningLanguage, nativeLanguage, layout, knownCharacters),
            content = listOf(image, instruction),
            schema = ClaudePrompts.pageSchema,
            serializer = PageAnalysisResult.serializer(),
            maxTokens = 16_000L,
        )
    }

    /** Translates [lines] one-to-one. The result always has the same size as [lines]. */
    fun translate(lines: List<String>, learningLanguage: String, nativeLanguage: String): List<String> {
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.mapIndexed { i, line -> "${i + 1}. ${line.replace('\n', ' ')}" }.joinToString("\n")
        val result = callJson(
            system = ClaudePrompts.translateSystem(learningLanguage, nativeLanguage),
            content = listOf(ContentBlockParam.ofText(TextBlockParam.builder().text(numbered).build())),
            schema = ClaudePrompts.translationSchema,
            serializer = TranslationResult.serializer(),
            maxTokens = 8_000L,
        )
        return List(lines.size) { i -> result.translations.getOrNull(i).orEmpty() }
    }

    /** A tutor-style breakdown of one line: reading, literal and natural translation, words, grammar. */
    fun explain(
        line: String,
        context: List<String>,
        learningLanguage: String,
        nativeLanguage: String,
    ): ExplanationResult {
        return callJson(
            system = ClaudePrompts.explainSystem(learningLanguage, nativeLanguage),
            content = listOf(
                ContentBlockParam.ofText(
                    TextBlockParam.builder().text(ClaudePrompts.explainUser(line, context)).build(),
                ),
            ),
            schema = ClaudePrompts.explanationSchema,
            serializer = ExplanationResult.serializer(),
            maxTokens = 8_000L,
        )
    }

    private fun requireSettings(): Settings {
        val s = settings()
        if (s.apiKey.isBlank()) {
            throw ClaudeException("Add your Claude API key in Settings → Reading assistant first.")
        }
        return s
    }

    private fun <T> callJson(
        system: String,
        content: List<ContentBlockParam>,
        schema: Map<String, Any>,
        serializer: KSerializer<T>,
        maxTokens: Long,
    ): T {
        val s = requireSettings()
        val useEffort = ClaudeModels.supportsEffort(s.model)
        val message = try {
            send(s, system, content, schema, maxTokens, structured = true, effort = useEffort)
        } catch (e: ClaudeException) {
            // A custom model id may not take structured outputs or an effort level.
            // Retry once with a plain prompt and parse the JSON out of the text.
            val detail = (e.cause as? BadRequestException)?.message.orEmpty()
            if ("output_config" in detail || "format" in detail || "effort" in detail) {
                send(s, system, content, schema, maxTokens, structured = false, effort = false)
            } else {
                throw e
            }
        }

        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw ClaudeException("Claude declined to read this. Try again or switch to on-device text recognition.")
        }
        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
        if (text.isBlank()) {
            throw ClaudeException("Claude returned an empty answer. Try again.")
        }
        return try {
            json.decodeFromString(serializer, extractJsonObject(text))
        } catch (e: Exception) {
            if (stop == StopReason.MAX_TOKENS) {
                throw ClaudeException("The answer was too long and got cut off. Try again on a smaller area.", e)
            }
            throw ClaudeException("Couldn't read Claude's answer. Try again.", e)
        }
    }

    private fun send(
        s: Settings,
        system: String,
        content: List<ContentBlockParam>,
        schema: Map<String, Any>,
        maxTokens: Long,
        structured: Boolean,
        effort: Boolean,
    ): Message {
        val builder = MessageCreateParams.builder()
            .model(s.model)
            .maxTokens(maxTokens)
            .system(if (structured) system else "$system\n\nAnswer with a single JSON object and nothing else.")
            .addUserMessageOfBlockParams(content)

        if (structured || effort) {
            val config = OutputConfig.builder()
            if (effort) config.effort(OutputConfig.Effort.of(s.effort))
            if (structured) {
                config.format(
                    JsonOutputFormat.builder()
                        .schema(
                            JsonOutputFormat.Schema.builder()
                                .additionalProperties(schema.mapValues { (_, v) -> JsonValue.from(v) })
                                .build(),
                        )
                        .build(),
                )
            }
            builder.outputConfig(config.build())
        }

        if (ClaudeModels.wantsServerFallback(s.model)) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        return mapErrors(s.model) { client(s.apiKey).messages().create(builder.build()) }
    }

    private inline fun <T> mapErrors(model: String, block: () -> T): T {
        return try {
            block()
        } catch (e: ClaudeException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e, model)
        }
    }

    private fun mapError(e: Exception, model: String): ClaudeException {
        val message = when (e) {
            is UnauthorizedException -> "Claude rejected the API key. Check it in Settings → Reading assistant."
            is PermissionDeniedException -> "This API key isn't allowed to use $model."
            is NotFoundException -> "The model $model isn't available to this API key. Pick another model in settings."
            is RateLimitException -> "Claude's rate limit was reached. Wait a moment and try again."
            is BadRequestException -> "Claude couldn't process the request: ${e.message}"
            is AnthropicServiceException -> when (e.statusCode()) {
                402 -> "Your Anthropic account needs credit (billing error)."
                413 -> "The page image was too large to send."
                529 -> "Claude is overloaded right now. Try again in a minute."
                else -> "Claude returned an error (${e.statusCode()}). Try again."
            }
            is AnthropicIoException -> "Couldn't reach Claude. Check your internet connection."
            else -> "Claude request failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return ClaudeException(message, e)
    }

    companion object {
        /** Pulls the first top-level JSON object out of [text], tolerating surrounding prose or code fences. */
        fun extractJsonObject(text: String): String {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }
    }
}
