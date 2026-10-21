version = 3

cloudstream {
    language = "hi"
    description = "Universal TMDB discovery frontend with asynchronous multi-provider engine (Bollydrive R2/10Gbps, HDhub4u HubCloud/R2, V-Cloud)."
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
