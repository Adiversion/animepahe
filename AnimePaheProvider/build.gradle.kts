version = 2

cloudstream {
    language = "en"
    description = "AnimePahe anime provider with Kwik and Pahe stream resolvers."
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
        "AnimeMovie",
        "Anime",
        "OVA"
    )
    iconUrl = "https://animepahe.pw/favicon.ico"
}
