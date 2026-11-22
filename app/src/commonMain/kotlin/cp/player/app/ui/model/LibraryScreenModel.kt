package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.presentation.library.LibraryPresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

typealias LibraryUiState = cp.player.app.presentation.library.LibraryUiState

/** Voyager adapter; library state and requests remain in the framework-neutral presenter. */
class LibraryScreenModel(initialPlaylistId: Long? = null) : ScreenModel {
    private val presenter = LibraryPresenter(
        initialPlaylistId = initialPlaylistId,
        scope = screenModelScope,
        notify = UiEvents::notify,
    )

    val state: StateFlow<LibraryUiState> get() = presenter.state
    val refreshing: StateFlow<Boolean> get() = presenter.refreshing

    init {
        screenModelScope.launch {
            AppModel.albumFavoriteGeneration.drop(1).collect { presenter.refreshAlbums() }
        }
    }

    fun selectPlaylist(playlistId: Long?) = presenter.selectPlaylist(playlistId)
    fun selectTab(index: Int) = presenter.selectTab(index)
    fun refresh() = presenter.refresh()
    fun refreshAlbums() = presenter.refreshAlbums()
    fun isOwner(playlist: PlaylistSummary) = presenter.isOwner(playlist)
    fun deleteOrUnsubscribe(playlist: PlaylistSummary) = presenter.deleteOrUnsubscribe(playlist)
    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) = presenter.play(playlist, addOnly)
    fun download(playlist: PlaylistSummary) = presenter.download(playlist)
}
