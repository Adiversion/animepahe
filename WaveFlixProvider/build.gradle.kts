version = 2

cloudstream {
    language = "en"
    description = "WaveFlix TMDB discovery frontend with multi-server playback engine (Nxsha, ScreenScape, Peachify)."
    authors = listOf("Cloudstream Community")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/csext/builds/WaveFlix_icon.png"
}
