version = 4

cloudstream {
    language = "hi"
    description = "Official Rogmovies provider for Bollywood, Hollywood Hindi Dubbed, South Indian, and Web Series with V-Cloud R2 (seekable HTTP 206) & Bollydrive 10Gbps resolvers."
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
    iconUrl = "https://raw.githubusercontent.com/Adiversion/animepahe/builds/icon.png"
}
