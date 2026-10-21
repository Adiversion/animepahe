version = 1

cloudstream {
    language = "hi"
    description = "Universal TMDB discovery frontend with asynchronous multi-storage resolvers (Bollydrive 10Gbps Google Photos CDN, HDhub4u HubCloud)."
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
        "AsianDrama"
    )
    iconUrl = "https://raw.githubusercontent.com/Adiversion/animepahe/builds/BollyStream_icon.png"
}
