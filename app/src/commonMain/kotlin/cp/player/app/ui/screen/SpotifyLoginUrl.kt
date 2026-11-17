package cp.player.app.ui.screen

/** Convert librespot device-code links to a browser-openable Spotify pairing URL. */
internal fun spotifyBrowserLoginUrl(raw: String): String {
    val value = raw.trim()
    if (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
        return value
    }
    val code = Regex("""code=([A-Za-z0-9_-]+)""").find(value)?.groupValues?.getOrNull(1)
        ?: value.takeIf { Regex("""[A-Za-z0-9_-]{4,16}""").matches(it) }
    return code?.let { "https://spotify.com/pair?code=$it" } ?: value
}
