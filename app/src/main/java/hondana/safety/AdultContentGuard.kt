package hondana.safety

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import exh.source.ExhPreferences
import exh.source.MANGADEX_IDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Background side of [AdultContentFilter], started from `App.onCreate`:
 * - keeps TachiyomiSY's integrated E-Hentai features switched off (a restored backup can't
 *   turn them back on), and upstream's extension NSFW preference on (its switch is removed);
 * - keeps MangaDex's own content-rating filter at Safe + Suggestive, so its listings never
 *   include Erotica or Pornographic titles;
 * - deletes blocked titles from the database, with their downloads: at startup, and whenever
 *   the library changes (a restore, a sync, or a library update that fetched new genres).
 */
object AdultContentGuard {

    private const val MANGADEX_RATING_KEY_PREFIX = "contentRating_"
    private val mangaDexAllowedRatings = setOf("safe", "suggestive")

    private val purgeMutex = Mutex()

    /** Titles open in the details screen or reader; deleted when they close, not under them. */
    private val openMangaIds = ConcurrentHashMap.newKeySet<Long>()

    private lateinit var scope: CoroutineScope

    // SharedPreferences keeps listeners weakly.
    private val mangaDexListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun start(appScope: CoroutineScope) {
        scope = appScope

        val hentaiFeatures = Injekt.get<ExhPreferences>().isHentaiEnabled()
        hentaiFeatures.changes()
            .onEach { if (it) hentaiFeatures.set(false) }
            .launchIn(appScope)

        // Upstream's "NSFW content" switch is gone. Left off (say, by a restored backup) it would
        // hide every extension rated as hosting any adult titles, general sites like MangaDex
        // included; adult-only sites are blocked by AdultContentFilter either way. Set before the
        // extension loader first reads it.
        val showRatedExtensions = Injekt.get<SourcePreferences>().showNsfwSource()
        if (!showRatedExtensions.get()) showRatedExtensions.set(true)
        showRatedExtensions.changes()
            .onEach { if (!it) showRatedExtensions.set(true) }
            .launchIn(appScope)

        appScope.launch(Dispatchers.IO) {
            enforceMangaDexRatings(Injekt.get<Application>())

            Injekt.get<SourceManager>().isInitialized.first { it }
            purge(null)
            Injekt.get<MangaRepository>().getLibraryMangaAsFlow()
                .onEach { library -> purge(library.map { it.manga }) }
                .launchIn(this)
        }
    }

    fun purgeSoon() {
        if (!::scope.isInitialized) return
        scope.launch(Dispatchers.IO) { purge(null) }
    }

    fun hold(mangaId: Long) {
        openMangaIds += mangaId
    }

    /** The screen showing [mangaId] closed: delete it if it's blocked. */
    fun release(mangaId: Long) {
        openMangaIds -= mangaId
        if (!::scope.isInitialized) return
        scope.launch(Dispatchers.IO) {
            delay(1_000)
            val manga = runCatching { Injekt.get<MangaRepository>().getMangaById(mangaId) }.getOrNull()
            if (manga != null) purge(listOf(manga))
        }
    }

    /** Deletes blocked titles among [candidates], or among every title in the database when null. */
    suspend fun purge(candidates: List<Manga>?) = purgeMutex.withLock {
        val mangaRepository = Injekt.get<MangaRepository>()
        val sourceManager = Injekt.get<SourceManager>()
        val downloadManager = Injekt.get<DownloadManager>()
        val all = candidates ?: runCatching { mangaRepository.getAll() }.getOrElse { return@withLock }
        val blocked = all.filter { manga ->
            manga.id !in openMangaIds &&
                AdultContentFilter.blocksManga(manga, sourceManager.getOrStub(manga.source).name)
        }
        if (blocked.isEmpty()) return@withLock
        logcat(LogPriority.INFO) { "Removing ${blocked.size} titles with sexual content" }
        blocked.forEach { manga ->
            runCatching { downloadManager.deleteManga(manga, sourceManager.getOrStub(manga.source)) }
            runCatching { mangaRepository.deleteManga(manga.id) }
                .onFailure { logcat(LogPriority.WARN, it) { "Couldn't remove ${manga.title}" } }
        }
    }

    private fun enforceMangaDexRatings(context: Context) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        MANGADEX_IDS.forEach { sourceId ->
            val name = "source_$sourceId"
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            // Only touch MangaDex languages that have been used; getSharedPreferences above
            // doesn't create a file until something is written.
            if (File(prefsDir, "$name.xml").exists()) stripAdultRatings(prefs)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
                if (key != null && key.startsWith(MANGADEX_RATING_KEY_PREFIX)) stripAdultRatings(changed)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            mangaDexListeners += listener
        }
    }

    private fun stripAdultRatings(prefs: SharedPreferences) {
        val edits = prefs.all
            .filterKeys { it.startsWith(MANGADEX_RATING_KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val ratings = (value as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: return@mapNotNull null
                val allowed = ratings.intersect(mangaDexAllowedRatings)
                if (allowed == ratings) null else key to allowed.ifEmpty { mangaDexAllowedRatings }
            }
        if (edits.isEmpty()) return
        prefs.edit().apply { edits.forEach { (key, ratings) -> putStringSet(key, ratings) } }.apply()
    }
}
