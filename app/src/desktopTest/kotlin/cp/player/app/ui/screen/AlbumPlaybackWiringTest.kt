package cp.player.app.ui.screen

import cp.player.core.music.CPMediaId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlbumPlaybackWiringTest {
    private fun source(): String {
        val path = "app/src/commonMain/kotlin/cp/player/app/ui/screen/AlbumDetailScreen.kt"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("Cannot locate AlbumDetailScreen.kt")
    }

    @Test
    fun canonicalTrackIdIsNotProviderPrefixedTwice() {
        val id = "netease-cloudmusic-rs://song/186016"
        assertEquals(id, CPMediaId.toCanonicalMediaId(id, "netease-cloudmusic-rs"))
        assertEquals("186016", CPMediaId.parseOrNull(id)?.resourceId)
        assertEquals(id, CPMediaId.toCanonicalMediaId("186016", "netease-cloudmusic-rs"))
    }

    @Test
    fun albumDetailPlaybackUsesCanonicalTrackIdsAndFilteredIndices() {
        val text = source()
        assertTrue("filteredTracks.map { AppModel.toMediaId(it.id, provider) }" in text)
        assertTrue("filteredTracks.indexOfFirst { it.id == track.id }" in text)
        assertFalse("\"\$provider://song/\${it.id}\"" in text)
    }
}
