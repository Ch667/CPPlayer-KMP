package cp.player.app.ui.component

import cp.player.core.music.ArtistSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistPickerResolutionTest {

    @Test
    fun `structured artists preserve order and filter missing ids`() {
        val resolved = resolveClickableArtists(
            artists = listOf(
                ArtistSummary(1L, "Artist A", null, "1"),
                ArtistSummary(0L, "No Id", null, ""),
                ArtistSummary(2L, "Artist B", null, "2"),
            ),
            fallbackName = "Artist A / No Id / Artist B",
            fallbackId = 99L,
            fallbackResourceId = "99",
        )
        assertEquals(listOf("1", "2"), resolved.map { it.resourceId })
        assertEquals(listOf("Artist A", "Artist B"), resolved.map { it.name })
    }

    @Test
    fun `falls back to single artist when structured list is empty`() {
        val resolved = resolveClickableArtists(
            artists = emptyList(),
            fallbackName = "Splitter",
            fallbackId = 12345L,
            fallbackResourceId = "12345",
        )
        assertEquals(1, resolved.size)
        assertEquals("Splitter", resolved.single().name)
        assertEquals("12345", resolved.single().resourceId)
    }

    @Test
    fun `returns empty when both structured list and fallback id are missing`() {
        val resolved = resolveClickableArtists(
            artists = emptyList(),
            fallbackName = "Splitter",
            fallbackId = null,
            fallbackResourceId = null,
        )
        assertEquals(emptyList(), resolved)
    }
}
