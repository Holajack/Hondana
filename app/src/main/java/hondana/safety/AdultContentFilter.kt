package hondana.safety

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import exh.source.EH_OLD_ID
import exh.source.EH_PACKAGE
import exh.source.EIGHTMUSES_SOURCE_ID
import exh.source.EXH_OLD_ID
import exh.source.HBROWSE_OLD_ID
import exh.source.HBROWSE_SOURCE_ID
import exh.source.NHENTAI_OLD_ID
import exh.source.NHENTAI_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.TSUMINO_OLD_ID
import exh.source.TSUMINO_SOURCE_ID
import exh.source.eHentaiSourceIds
import hondana.core.Hondana
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * Hondana's sexual-content filter. Always on; there is no setting to turn it off.
 *
 * It blocks porn and hentai and leaves violence and gore alone:
 * - Extensions a repo rates NSFW (adult-only sites) never load, never show in the extension
 *   list and can't be installed, even when another app such as Mihon installed them.
 *   Extensions rated MIXED (general sites that also host adult titles, e.g. MangaDex) stay.
 *   Repos without ratings fall back to [AdultContentRules.isAdultSourceName].
 * - Adult-only sources inside an allowed extension ("Shadow Manga (+18)") are dropped.
 * - Titles tagged as sexual content are hidden everywhere and removed from the library.
 *
 * Source IDs and packages of blocked extensions are remembered, so backups and old library
 * entries from those sources can be recognised after the extension is gone.
 */
object AdultContentFilter {

    /** TachiyomiSY's built-in E-Hentai/ExHentai sources stay unregistered. */
    val allowsBuiltInHentai = false

    private val knownAdultSourceIds: Set<Long> = eHentaiSourceIds + setOf(
        EH_OLD_ID, EXH_OLD_ID, NHENTAI_SOURCE_ID, NHENTAI_OLD_ID, PURURIN_SOURCE_ID, TSUMINO_SOURCE_ID,
        TSUMINO_OLD_ID, EIGHTMUSES_SOURCE_ID, HBROWSE_SOURCE_ID, HBROWSE_OLD_ID,
    )

    @Volatile private var cachedSourceIds: Set<Long>? = null

    @Volatile private var cachedPackages: Set<String>? = null

    fun blockedSourceIds(): Set<Long> = cachedSourceIds ?: (
        knownAdultSourceIds + Hondana.preferences.blockedSourceIds().get().mapNotNull(String::toLongOrNull)
        ).also { cachedSourceIds = it }

    private fun blockedPackages(): Set<String> = cachedPackages ?: (
        Hondana.preferences.blockedExtensionPackages().get() + EH_PACKAGE
        ).also { cachedPackages = it }

    /**
     * Whether a whole extension is blocked.
     *
     * @param adultOnly the repo's rating: true for NSFW, false for SAFE or MIXED, null when the
     *   repo or the installed APK doesn't say (legacy repos mark SAFE vs "some NSFW" only).
     */
    fun blocksExtension(pkgName: String, name: String, adultOnly: Boolean?): Boolean {
        if (adultOnly == true || pkgName in blockedPackages()) return true
        return adultOnly == null &&
            (AdultContentRules.isAdultSourceName(name) || AdultContentRules.isAdultSourceName(pkgName.substringAfterLast('.')))
    }

    fun blocksSource(source: Source): Boolean =
        source.id in blockedSourceIds() || isAdultSourceName(source.name)

    fun blocksSourceId(sourceId: Long, sourceName: String? = null): Boolean =
        sourceId in blockedSourceIds() || isAdultSourceName(sourceName)

    // Library cleanup checks every title's source name on each library change; names repeat.
    private val sourceNameVerdicts = ConcurrentHashMap<String, Boolean>()

    private fun isAdultSourceName(name: String?): Boolean =
        !name.isNullOrBlank() && sourceNameVerdicts.getOrPut(name) { AdultContentRules.isAdultSourceName(name) }

    /** Hidden in lists, refused in the details screen and reader, removed from the library. */
    fun blocksManga(manga: Manga, sourceName: String? = null): Boolean =
        blocksSourceId(manga.source, sourceName) || AdultContentRules.isSexualTitle(manga.title, manga.genre)

    fun blocksManga(sourceId: Long, sourceName: String?, title: String, genres: List<String>?): Boolean =
        blocksSourceId(sourceId, sourceName) || AdultContentRules.isSexualTitle(title, genres)

    /** An installed extension that a newer repo index now marks as adult-only. */
    fun blocksInstalledExtension(extension: Extension.Installed): Boolean =
        extension.pkgName in blockedPackages() ||
            (extension.sources.isNotEmpty() && extension.sources.all { blocksSource(it) })

    /**
     * Drops blocked extensions and adult-only sources from a repo listing, and remembers what was
     * dropped so the loader, backup restore and library cleanup recognise it later.
     */
    fun screenAvailableExtensions(extensions: List<Extension.Available>): List<Extension.Available> {
        val newPackages = mutableSetOf<String>()
        val newSourceIds = mutableSetOf<Long>()
        val allowed = extensions.mapNotNull { extension ->
            if (blocksExtension(extension.pkgName, extension.name, extension.hondanaAdultOnly)) {
                newPackages += extension.pkgName
                newSourceIds += extension.sources.map { it.id }
                return@mapNotNull null
            }
            val (adult, general) = extension.sources.partition {
                it.id in blockedSourceIds() || AdultContentRules.isAdultSourceName(it.name)
            }
            newSourceIds += adult.map { it.id }
            when {
                adult.isEmpty() -> extension
                general.isEmpty() -> {
                    newPackages += extension.pkgName
                    null
                }
                else -> extension.copy(sources = general)
            }
        }
        remember(newPackages, newSourceIds - 0L)
        return allowed
    }

    private fun remember(packages: Set<String>, sourceIds: Set<Long>) {
        val preferences = Hondana.preferences
        val storedPackages = preferences.blockedExtensionPackages().get()
        if (!storedPackages.containsAll(packages)) {
            preferences.blockedExtensionPackages().set(storedPackages + packages)
            cachedPackages = null
        }
        val storedIds = preferences.blockedSourceIds().get()
        val ids = sourceIds.map(Long::toString)
        if (!storedIds.containsAll(ids)) {
            preferences.blockedSourceIds().set(storedIds + ids)
            cachedSourceIds = null
            // Library entries from the newly recognised sources go now, not at the next launch.
            AdultContentGuard.purgeSoon()
        }
    }
}
