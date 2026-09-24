package hondana.extensions

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import exh.source.LOCAL_SOURCE_PACKAGE
import exh.source.MERGED_SOURCE_ID
import hondana.core.Hondana
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.REPO_SIGNATURE
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Keeps Hondana's extension repos in step with Mihon.
 *
 * Backups from older Mihon versions describe Keiyoushi in its legacy form (`index.min.json` /
 * `repo.json`). Keiyoushi's legacy index now lists only two "update your app" placeholders, so a
 * restored legacy entry shows almost nothing, and extensions another app installed stay
 * "Untrusted" until a repo with their signing key is present. At every launch this:
 * - moves legacy repo entries onto their current index (the repo's `repo.json` points at it);
 * - adds Keiyoushi's index if no repo carries Keiyoushi's signing key, and drops leftover
 *   Keiyoushi entries that can't be fetched;
 * - loads installed extensions a repo vouches for that were held as untrusted;
 * - refreshes the list of available extensions.
 */
object ExtensionSetup {

    const val KEIYOUSHI_INDEX_URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"

    /** Signing key recorded for repos migrated from old preferences; such entries can't be fetched. */
    private const val NO_SIGNING_KEY = "NO_SIGNING_KEY"

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val changed = runCatching { repairStores() }
                .onFailure { logcat(LogPriority.WARN, it) { "Couldn't repair extension repos" } }
                .getOrDefault(false)
            runCatching { loadVouchedExtensions() }
                .onFailure { logcat(LogPriority.WARN, it) { "Couldn't load trusted extensions" } }
            // Refresh the listing when the repos changed, and once so the content filter learns
            // which sources are adult-only.
            if (changed || Hondana.preferences.blockedSourceIds().get().isEmpty()) {
                runCatching { Injekt.get<ExtensionManager>().findAvailableExtensions() }
            }
        }
    }

    /** Returns true when a repo entry was upgraded, added or removed. */
    private suspend fun repairStores(): Boolean {
        val getStores = Injekt.get<GetExtensionStores>()
        val before = getStores.get()
        if (before.any { it.isLegacy }) {
            Injekt.get<UpdateExtensionStores>()()
        }
        if (getStores.get().none { it.signingKey == REPO_SIGNATURE }) {
            Injekt.get<AddExtensionStore>()(KEIYOUSHI_INDEX_URL)
                .onFailure { logcat(LogPriority.WARN, it) { "Couldn't add the Keiyoushi repo" } }
        }
        if (getStores.get().any { it.signingKey == REPO_SIGNATURE }) {
            val removeStore = Injekt.get<RemoveExtensionStore>()
            getStores.get()
                .filter { it.signingKey == NO_SIGNING_KEY && "keiyoushi" in it.indexUrl.lowercase() }
                .forEach { removeStore(it.indexUrl) }
        }
        return getStores.get() != before
    }

    /** Extensions another app installed sit under "Untrusted" until a repo with their key exists. */
    private suspend fun loadVouchedExtensions() {
        val extensionManager = Injekt.get<ExtensionManager>()
        extensionManager.isInitialized.first { it }
        val repoKeys = Injekt.get<GetExtensionStores>().get().map { it.signingKey }.toSet()
        currentList(extensionManager.untrustedExtensionsFlow)
            .filter { it.signatureHash in repoKeys }
            .forEach { extensionManager.trust(it) }
    }

    /** Extensions from the repos that provide sources your library uses but that aren't installed. */
    suspend fun missingLibraryExtensions(): List<Extension.Available> {
        val extensionManager = Injekt.get<ExtensionManager>()
        val sourceManager = Injekt.get<SourceManager>()
        extensionManager.findAvailableExtensions()
        val missingSourceIds = Injekt.get<MangaRepository>().getFavorites()
            .map { it.source }
            .toSet()
            .filter { id ->
                id != MERGED_SOURCE_ID && id != LocalSource.ID && sourceManager.getOrStub(id) is StubSource
            }
            .toSet()
        if (missingSourceIds.isEmpty()) return emptyList()
        return currentList(extensionManager.availableExtensionsFlow)
            .filter { extension -> extension.pkgName != LOCAL_SOURCE_PACKAGE }
            .filter { extension -> extension.sources.any { it.id in missingSourceIds } }
            .distinctBy { it.pkgName }
    }

    /**
     * ExtensionManager's list flows only start following their source once something collects
     * them (the Extensions tab does), so `.value` alone can be stale. Collect briefly instead.
     */
    private suspend fun <T> currentList(flow: StateFlow<List<T>>): List<T> =
        withTimeoutOrNull(3_000) { flow.first { it.isNotEmpty() } } ?: flow.value

    /** Installs [extensions] one after another; returns how many finished installing. */
    suspend fun install(extensions: List<Extension.Available>): Int {
        val extensionManager = Injekt.get<ExtensionManager>()
        var installed = 0
        extensions.forEach { extension ->
            val result = runCatching {
                extensionManager.installExtension(extension).first { it.isCompleted() }
            }.getOrDefault(InstallStep.Error)
            if (result == InstallStep.Installed) installed++
        }
        return installed
    }
}
