package cp.player.app.presentation.library

import cp.player.app.presentation.library.LibraryUiState
import cp.player.core.music.AlbumSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPresenterAlbumsTest {
    @Test
    fun stateCarriesStringResourceIdsForCollectedAlbums() {
        val summary = AlbumSummary(
            id = 0, name = "Spotify release", coverUrl = null, artistName = "Artist", trackCount = 12,
            resourceId = "spotify-album-opaque-id",
        )
        val state = LibraryUiState(favoriteAlbums = listOf(summary))
        assertEquals("spotify-album-opaque-id", state.favoriteAlbums.single().resourceId)
    }
}
