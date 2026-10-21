version = 2

cloudstream {
    language = "hi"
    description = "Universal TMDB discovery frontend with asynchronous multi-storage resolvers (V-Cloud R2 206 seekable stream, Bollydrive 10Gbps CDN, HDhub4u HubCloud)."
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
