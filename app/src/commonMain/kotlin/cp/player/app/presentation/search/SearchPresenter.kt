package cp.player.app.presentation.search

import cp.player.app.AppModel
import cp.player.app.presentation.UiPresenter
import cp.player.app.repository.HotSearch
import cp.player.app.repository.MusicRepository
import cp.player.app.repository.SearchType
import cp.player.core.BackendResult
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryAggregator
import cp.player.core.media.MediaType
import cp.player.core.media.LocalMediaItem
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.MusicResult
import cp.player.core.music.SearchResult
import cp.player.core.music.TrackSummary
import cp.player.core.provider.BackendProvider
import cp.player.core.util.SettingsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val SEARCH_HISTORY_KEY = "search_history"
private const val MAX_SEARCH_HISTORY = 30

data class SearchUiState(
    val query: String = "",
    val searchType: Int = SearchType.SONG,
    val result: SearchResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hotSearches: List<HotSearch> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

/**
 * 框架无关的搜索业务状态呈现器。
 *
 * 任何 UI（无论是 Compose、Voyager ScreenModel、其他桌面前端还是 CLI）都可以直接实例化使用，
 * 状态全部通过强类型 [SearchUiState] 暴露，不需要手写解析任何 JSON。
 */
class SearchPresenter(
    private val initialQuery: String = "",
    override val scope: CoroutineScope,
    private val settingsProvider: () -> SettingsStorage = { AppModel.settings },
    private val repositoryProvider: () -> MusicRepository = { AppModel.musicRepository },
    private val activeProviderProvider: () -> BackendProvider? = { AppModel.activeProvider() },
    private val localItemsProvider: () -> List<LocalMediaItem> = { AppModel.localMedia.items().value },
    private val suggestionsLoader: suspend (String) -> List<String>? = ::loadSearchSuggestions,
    private val hotSearchesLoader: suspend () -> List<HotSearch> = {
        repositoryProvider().getHotSearches()
    },
) : UiPresenter<SearchUiState> {

    private val _state = MutableStateFlow(SearchUiState(query = initialQuery))
    override val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var suggestionJob: Job? = null
    private var hotSearchJob: Job? = null
    private var searchJob: Job? = null

    @Volatile
    private var searchGeneration = 0

    init {
        _state.value = _state.value.copy(searchHistory = readHistory())
        loadHotSearches()
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    private fun readHistory(): List<String> = settingsProvider()
        .getString(SEARCH_HISTORY_KEY)
        ?.split("\n")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        ?.take(MAX_SEARCH_HISTORY)
        ?: emptyList()

    private fun saveHistory(history: List<String>) {
        settingsProvider().putString(SEARCH_HISTORY_KEY, history.joinToString("\n"))
    }

    fun loadHotSearches() {
        hotSearchJob?.cancel()
        hotSearchJob = scope.launch {
            val hot = try {
                hotSearchesLoader()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                emptyList()
            }
            coroutineContext.ensureActive()
            _state.value = _state.value.copy(
                hotSearches = hot.ifEmpty {
                    listOf(HotSearch("热门歌曲"), HotSearch("流行音乐"), HotSearch("古典乐"))
                },
            )
        }
    }

    fun setQuery(query: String) {
        searchJob?.cancel()
        searchGeneration++
        _state.value = _state.value.copy(query = query, error = null, result = null, loading = false)
        suggestionJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(suggestions = emptyList())
            return
        }
        suggestionJob = scope.launch {
            val requestedQuery = query.trim()
            val suggestions = if (activeProviderProvider() == null) {
                val localItems = localItemsProvider()
                    .filter { it.mediaType == MediaType.AUDIO }
                LocalLibraryAggregator.search(localItems, requestedQuery).map { it.title }.distinct().take(10)
            } else {
                suggestionsLoader(requestedQuery).orEmpty()
            }
            if (_state.value.query.trim() != requestedQuery) return@launch
            _state.value = _state.value.copy(suggestions = suggestions)
        }
    }

    fun clear() {
        suggestionJob?.cancel()
        searchJob?.cancel()
        searchGeneration++
        _state.value = _state.value.copy(query = "", result = null, error = null, suggestions = emptyList(), loading = false)
    }

    fun clearHistory() {
        saveHistory(emptyList())
        _state.value = _state.value.copy(searchHistory = emptyList())
    }

    fun selectSearchType(type: Int) {
        if (type == _state.value.searchType) return
        _state.value = _state.value.copy(searchType = type)
        if (_state.value.query.isNotBlank()) search(type = type)
    }

    fun search(keyword: String = _state.value.query, type: Int = _state.value.searchType) {
        val finalKeyword = keyword.trim()
        if (finalKeyword.isEmpty()) {
            searchJob?.cancel()
            searchGeneration++
            _state.value = _state.value.copy(result = null, error = null, loading = false)
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        suggestionJob?.cancel()
        val history = listOf(finalKeyword) + _state.value.searchHistory.filterNot { it.equals(finalKeyword, true) }
        val trimmedHistory = history.take(MAX_SEARCH_HISTORY)
        saveHistory(trimmedHistory)
        _state.value = _state.value.copy(
            query = finalKeyword,
            searchType = type,
            searchHistory = trimmedHistory,
            suggestions = emptyList(),
            result = null,
        )
        searchJob = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            if (activeProviderProvider() == null) {
                val localItems = localItemsProvider()
                    .filter { it.mediaType == MediaType.AUDIO }
                val matchedSongs = LocalLibraryAggregator.search(localItems, finalKeyword).map { item ->
                    TrackSummary(
                        id = "local://audio/${item.path}",
                        name = item.title,
                        artist = item.artist ?: "未知歌手",
                        album = item.album,
                        coverUrl = item.coverUri,
                        durationMs = item.durationMs,
                    )
                }
                val matchedAlbums = LocalLibraryAggregator.albums(localItems, LocalGridSort.NAME, finalKeyword)
                    .map { album ->
                        AlbumSummary(
                            id = album.name.hashCode().toLong(),
                            name = album.name,
                            artistName = album.artist,
                            coverUrl = album.coverUri,
                            trackCount = album.songCount,
                        )
                    }
                val matchedArtists = LocalLibraryAggregator.artists(localItems, LocalGridSort.NAME, finalKeyword)
                    .map { artist ->
                        ArtistSummary(
                            id = artist.name.hashCode().toLong(),
                            name = artist.name,
                            avatarUrl = artist.coverUri,
                        )
                    }
                if (generation != searchGeneration) return@launch
                _state.value = _state.value.copy(
                    result = SearchResult(
                        songs = matchedSongs,
                        playlists = emptyList(),
                        artists = matchedArtists,
                        albums = matchedAlbums,
                    ),
                    loading = false,
                )
                return@launch
            }
            val response = try {
                searchWithTransientRetry(
                    keyword = finalKeyword,
                    type = type,
                    isCurrent = { generation == searchGeneration },
                    onRefresh = { refreshed ->
                        val updated = (refreshed as? BackendResult.Success)?.data
                        if (updated != null && generation == searchGeneration &&
                            _state.value.query == finalKeyword && _state.value.searchType == type
                        ) {
                            _state.value = _state.value.copy(result = updated)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BackendResult.Error(error.message ?: "搜索失败")
            }
            if (generation != searchGeneration) return@launch
            _state.value = when (response) {
                is BackendResult.Success -> _state.value.copy(result = response.data, loading = false)
                is BackendResult.Error -> _state.value.copy(error = friendlySearchError(response.message), loading = false)
                is BackendResult.Unsupported -> _state.value.copy(error = response.message, loading = false)
            }
        }
    }

    private suspend fun searchWithTransientRetry(
        keyword: String,
        type: Int,
        isCurrent: () -> Boolean,
        onRefresh: (MusicResult<SearchResult>) -> Unit,
    ): MusicResult<SearchResult> {
        val backoffs = longArrayOf(350L, 900L)
        var response = repositoryProvider().search(keyword, type, onRefresh = onRefresh)
        for (waitMs in backoffs) {
            if (!isCurrent() || !isTransientSearchError(response)) return response
            delay(waitMs)
            if (!isCurrent()) return response
            response = repositoryProvider().search(keyword, type, onRefresh = onRefresh)
        }
        return response
    }

    private fun isTransientSearchError(result: MusicResult<SearchResult>): Boolean {
        val rawMessage = (result as? BackendResult.Error)?.message
        val msg = rawMessage?.lowercase() ?: return false
        return "network" in msg || "timeout" in msg || "rate_limit" in msg || "rate limit" in msg ||
            "connect" in msg || "socket" in msg || "temporar" in msg || "invalidresponse" in msg ||
            "invalid response" in msg || "缓存已失效" in rawMessage
    }

    private fun friendlySearchError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "network" in lower || "connect" in lower || "socket" in lower ->
                "网络波动，自动重试后仍未成功，请点击重试"
            "timeout" in lower ->
                "搜索响应超时，请稍后重试"
            "rate_limit" in lower || "rate limit" in lower ->
                "请求过于频繁，请稍后重试"
            else -> raw
        }
    }

    internal fun shouldRetrySearchError(result: MusicResult<SearchResult>): Boolean =
        isTransientSearchError(result)
}
