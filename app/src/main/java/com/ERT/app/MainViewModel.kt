package com.ERT.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ERT.app.core.Http
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.core.PageResult
import com.ERT.app.core.Repo
import com.ERT.app.core.Requests
import com.ERT.app.core.SourceStat
import com.ERT.app.ui.Load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One ViewModel for the whole app (single Activity, bottom navigation): it owns the feed holders,
 * the detail view, settings and the Diagnostics run.
 *
 * Two rules are enforced here because they are what keeps the UI honest:
 *  - **Retry always forces a refresh**, so a cached entry can never mask a fix;
 *  - **every job is bound to a slot**, so one slow page cannot starve another screen, and the user
 *    can cancel any of them.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repo(app)

    /** Set by an error block (e.g. rate-limited) to ask the UI to open Settings. */
    var pendingSettingsRequest by mutableStateOf(false)

    // ------------------------------------------------------------------ feeds

    /** Owns one browsable list: current query, accumulated pages, loading/error state. */
    inner class FeedHolder(val slot: String, initial: Requests.FeedQuery) {
        var query by mutableStateOf(initial)
            private set

        private val _state = MutableStateFlow<Load<PageResult>>(Load.Idle)
        val state: StateFlow<Load<PageResult>> = _state.asStateFlow()

        private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
        val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

        private var page: PageResult? = null

        /** Replace the query (a new tab filter / search term) and reload from page 1. */
        fun setQuery(q: Requests.FeedQuery, force: Boolean = false) {
            query = q
            page = null
            _items.value = emptyList()
            // Not forced: with an anonymous budget of 5 requests/hour, re-selecting a filter you
            // just looked at should be free. Retry is the only thing that always refetches.
            load(force = false)
        }

        fun load(force: Boolean = false) = launchSlot {
            val refreshing = !force && _items.value.isNotEmpty()
            _state.value = Load.Busy(query.toUrl(), refreshing)
            try {
                val result = repo.feed(slot, query, force)
                page = result.page
                _items.value = result.page.items
                _state.value = Load.Ready(result.page, result.url, result.fromCache)
            } catch (e: CancellationException) {
                _state.value = if (_items.value.isEmpty()) Load.Idle else Load.Ready(page!!, query.toUrl(), true)
                throw e
            } catch (e: Throwable) {
                val err = e as? com.ERT.app.core.ApiError
                    ?: com.ERT.app.core.ApiError(-3, query.toUrl(), e.message ?: "Unknown error")
                _state.value = Load.Failed(err)
            }
        }

        fun loadMore() {
            val next = page?.nextPage ?: return
            if (_state.value is Load.Busy) return
            val nextQuery = query.copy(page = next)
            launchSlot(extra = "_more") {
                _state.value = Load.Busy(nextQuery.toUrl(), refreshing = true)
                try {
                    val result = repo.feed(slot, nextQuery, force = false)
                    page = result.page
                    _items.value = _items.value + result.page.items
                    _state.value = Load.Ready(result.page, result.url, result.fromCache)
                } catch (e: CancellationException) {
                    _state.value = Load.Ready(page!!, query.toUrl(), true)
                    throw e
                } catch (e: Throwable) {
                    val err = e as? com.ERT.app.core.ApiError
                        ?: com.ERT.app.core.ApiError(-3, nextQuery.toUrl(), e.message ?: "Unknown error")
                    _state.value = Load.Failed(err)
                }
            }
        }

        fun retry() = load(force = true)
        fun cancel() = repo.cancel(slot)

        private fun launchSlot(extra: String = "", block: suspend () -> Unit): Job =
            viewModelScope.launch { block() }
    }

    val images = FeedHolder(Repo.SLOT_IMAGES, Requests.browse(MediaType.IMAGE))
    val audio = FeedHolder(Repo.SLOT_AUDIO, Requests.browse(MediaType.AUDIO))
    val search = FeedHolder(Repo.SLOT_SEARCH, Requests.search(MediaType.IMAGE, ""))

    var searchText by mutableStateOf("")
        private set

    fun onSearchTextChanged(v: String) {
        searchText = v
    }

    fun submitSearch(type: MediaType) {
        if (searchText.isBlank()) return
        search.setQuery(
            Requests.FeedQuery(type = type, q = searchText.trim(), mature = repo.store.mature)
        )
    }

    // ----------------------------------------------------------------- sources

    private val _sources = MutableStateFlow<Load<List<SourceStat>>>(Load.Idle)
    val sources: StateFlow<Load<List<SourceStat>>> = _sources.asStateFlow()

    var sourceFilter by mutableStateOf("")
        private set

    fun onSourceFilterChanged(v: String) { sourceFilter = v }

    fun loadSources(force: Boolean = false) {
        viewModelScope.launch {
            _sources.value = Load.Busy(Requests.stats(MediaType.IMAGE), _sources.value is Load.Ready)
            try {
                val list = repo.stats(Repo.SLOT_STATS, MediaType.IMAGE, force)
                _sources.value = Load.Ready(list, Requests.stats(MediaType.IMAGE), !force && false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = e as? com.ERT.app.core.ApiError
                    ?: com.ERT.app.core.ApiError(-3, Requests.stats(MediaType.IMAGE), e.message ?: "Unknown error")
                _sources.value = Load.Failed(err)
            }
        }
    }

    /** Tapping a provider browses that provider: source=<name> on the images feed. */
    fun browseBySource(source: String) {
        images.setQuery(
            Requests.FeedQuery(type = MediaType.IMAGE, source = source, mature = repo.store.mature)
        )
    }

    // ------------------------------------------------------------------ detail

    var selected by mutableStateOf<MediaItem?>(null)
        private set

    private val _detail = MutableStateFlow<Load<MediaItem>>(Load.Idle)
    val detail: StateFlow<Load<MediaItem>> = _detail.asStateFlow()

    private val _related = MutableStateFlow<Load<PageResult>>(Load.Idle)
    val related: StateFlow<Load<PageResult>> = _related.asStateFlow()

    fun openDetail(item: MediaItem) {
        selected = item
        repo.store.recordView(item)
        refreshBookmarks()
        refreshHistory()
        loadDetail(item.mediaType, item.id, force = false)
    }

    fun loadDetail(type: MediaType, id: String, force: Boolean = false) {
        viewModelScope.launch {
            _detail.value = Load.Busy(Requests.detail(type, id), _detail.value is Load.Ready)
            try {
                val item = repo.detail(Repo.SLOT_DETAIL, type, id, force)
                selected = item
                _detail.value = Load.Ready(item, Requests.detail(type, id), !force && false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = e as? com.ERT.app.core.ApiError
                    ?: com.ERT.app.core.ApiError(-3, Requests.detail(type, id), e.message ?: "Unknown error")
                _detail.value = Load.Failed(err)
            }
        }
        loadRelated(type, id, force)
    }

    private fun loadRelated(type: MediaType, id: String, force: Boolean) {
        viewModelScope.launch {
            _related.value = Load.Busy(Requests.related(type, id), true)
            try {
                val page = repo.related(Repo.SLOT_RELATED, type, id, force)
                _related.value = Load.Ready(page, Requests.related(type, id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = e as? com.ERT.app.core.ApiError
                    ?: com.ERT.app.core.ApiError(-3, Requests.related(type, id), e.message ?: "Unknown error")
                _related.value = Load.Failed(err)
            }
        }
    }

    fun cancelDetail() {
        repo.cancel(Repo.SLOT_DETAIL)
        repo.cancel(Repo.SLOT_RELATED)
    }

    // ------------------------------------------------------- bookmarks/history

    private val _bookmarks = MutableStateFlow<List<MediaItem>>(emptyList())
    val bookmarks: StateFlow<List<MediaItem>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<Pair<MediaItem, Long>>>(emptyList())
    val history: StateFlow<List<Pair<MediaItem, Long>>> = _history.asStateFlow()

    fun refreshBookmarks() { _bookmarks.value = repo.store.bookmarks() }
    fun refreshHistory() { _history.value = repo.store.history() }

    fun toggleBookmark(item: MediaItem) {
        repo.store.toggleBookmark(item)
        refreshBookmarks()
    }

    fun clearHistory() {
        repo.store.clearHistory()
        refreshHistory()
    }

    // -------------------------------------------------------------- settings

    var mature: Boolean
        get() = repo.store.mature
        set(v) { repo.store.mature = v }

    var clientId by mutableStateOf(repo.store.clientId ?: "")
        private set
    var clientSecret by mutableStateOf(repo.store.clientSecret ?: "")
        private set

    fun onClientIdChanged(v: String) { clientId = v }
    fun onClientSecretChanged(v: String) { clientSecret = v }

    private val _keyStatus = MutableStateFlow("")
    val keyStatus: StateFlow<String> = _keyStatus.asStateFlow()

    fun saveKey() {
        repo.store.clientId = clientId.trim()
        repo.store.clientSecret = clientSecret.trim()
        _keyStatus.value = "Saved. Testing…"
        testKey()
    }

    fun testKey() {
        viewModelScope.launch {
            _keyStatus.value = "Requesting token…"
            try {
                val info = repo.fetchToken(clientId.trim(), clientSecret.trim())
                repo.store.saveToken(info)
                val limits = repo.rateLimitSummary()
                _keyStatus.value = "Signed in. Token valid for ${info.expiresInSec / 60} min.\n$limits"
            } catch (e: Throwable) {
                _keyStatus.value = "Failed: ${e.message}"
            }
        }
    }

    fun clearKey() {
        repo.store.clearCredentials()
        clientId = ""
        clientSecret = ""
        _keyStatus.value = "Signed out (anonymous: 5 requests/hour)."
    }

    // ----------------------------------------------------------- diagnostics

    var diagPath by mutableStateOf(Requests.PRESETS.first().path)
        private set
    var diagUseCache by mutableStateOf(false)
        private set

    fun onDiagPathChanged(v: String) { diagPath = v }
    fun onDiagUseCacheChanged(v: Boolean) { diagUseCache = v }

    private val _diag = MutableStateFlow<Load<Repo.RawResult>>(Load.Idle)
    val diag: StateFlow<Load<Repo.RawResult>> = _diag.asStateFlow()

    fun runDiagnostics(force: Boolean = true) {
        viewModelScope.launch {
            _diag.value = Load.Busy(Requests.resolve(diagPath))
            try {
                val result = repo.raw(diagPath, force = force, allowCache = diagUseCache)
                _diag.value = Load.Ready(result, result.url, result.fromCache)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = e as? com.ERT.app.core.ApiError
                    ?: com.ERT.app.core.ApiError(-3, Requests.resolve(diagPath), e.message ?: "Unknown error")
                _diag.value = Load.Failed(err)
            }
        }
    }

    fun cancelDiagnostics() = repo.cancel(Repo.SLOT_RAW)

    fun clearCache() {
        repo.clearCache()
        _cacheSize.value = repo.cacheBytes()
    }

    private val _cacheSize = MutableStateFlow(repo.cacheBytes())
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    fun refreshCacheSize() { _cacheSize.value = repo.cacheBytes() }

    val hitsLastHour: Int get() = repo.store.hitsLastHour()

    override fun onCleared() {
        super.onCleared()
        Http.cancelAll()
    }
}
