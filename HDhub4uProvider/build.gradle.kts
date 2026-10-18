version = 1

cloudstream {
    language = "hi"
    description = "HDHub4u Bollywood, Hollywood, and Web Series provider with dynamic domain rotation, parallel stream resolution, and HD TMDB backdrops."
    authors = listOf("Adiversion")

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
        "Anime",
        "AsianDrama"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/animepahe/builds/icon.png"
}
