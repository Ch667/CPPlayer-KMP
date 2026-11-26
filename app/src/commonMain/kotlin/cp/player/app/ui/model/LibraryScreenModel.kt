package cp.player.app.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cp.player.app.presentation.library.LibraryPresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.flow.StateFlow

typealias LibraryUiState = cp.player.app.presentation.library.LibraryUiState

/** Lifecycle adapter; library state and requests remain in the framework-neutral presenter. */
class LibraryScreenModel(initialPlaylistId: Long? = null) : ViewModel() {
    private val presenter = LibraryPresenter(
        initialPlaylistId = initialPlaylistId,
        scope = viewModelScope,
        notify = UiEvents::notify,
    )

    val state: StateFlow<LibraryUiState> get() = presenter.state
    val refreshing: StateFlow<Boolean> get() = presenter.refreshing


    fun selectPlaylist(playlistId: Long?) = presenter.selectPlaylist(playlistId)
    fun selectTab(index: Int) = presenter.selectTab(index)
    fun refresh() = presenter.refresh()
    fun refreshAlbums() = presenter.refreshAlbums()
    fun isOwner(playlist: PlaylistSummary) = presenter.isOwner(playlist)
    fun deleteOrUnsubscribe(playlist: PlaylistSummary) = presenter.deleteOrUnsubscribe(playlist)
    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) = presenter.play(playlist, addOnly)
    fun download(playlist: PlaylistSummary) = presenter.download(playlist)
}
