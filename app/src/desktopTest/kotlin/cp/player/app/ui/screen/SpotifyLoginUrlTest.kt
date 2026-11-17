package cp.player.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyLoginUrlTest {
    @Test fun httpsPairUrlIsPreserved() = assertEquals(
        "https://spotify.com/pair?code=ABC123", spotifyBrowserLoginUrl("https://spotify.com/pair?code=ABC123"),
    )
    @Test fun spotifySchemeIsNormalized() = assertEquals(
        "https://spotify.com/pair?code=ABC123", spotifyBrowserLoginUrl("spotify:pair?code=ABC123"),
    )
    @Test fun plainDeviceCodeIsNormalized() = assertEquals(
        "https://spotify.com/pair?code=ABC123", spotifyBrowserLoginUrl("ABC123"),
    )
}
