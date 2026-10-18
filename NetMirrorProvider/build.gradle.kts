version = 2

cloudstream {
    language = "hi"
    description = "Curated Indian OTT Catalogs (Netflix, Prime Video, JioHotstar, Sony LIV, Zee5) with NetMirror Direct High-Speed CDN Playback."
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
