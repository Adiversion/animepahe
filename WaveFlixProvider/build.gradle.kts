version = 10

cloudstream {
    language = "en"
    description = "WaveFlix TMDB streaming engine with 45+ servers including BollyZone, RogMovies, NetMirror direct CDN, Vidlink Pro, and Nxsha multi-server playback."
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
