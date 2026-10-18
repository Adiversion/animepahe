version = 3

cloudstream {
    language = "hi"
    description = "Native NetMirror Curated & Discover Catalogs with High-Speed Direct CDN Streams."
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
    iconUrl = "https://raw.githubusercontent.com/Adiversion/animepahe/builds/NetMirror_icon.png"
}
