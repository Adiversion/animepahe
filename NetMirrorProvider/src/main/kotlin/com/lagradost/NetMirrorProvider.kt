package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class NetMirrorProvider : MainAPI() {

    companion object {
        const val DEFAULT_DOMAIN = "https://netmirrors.vip"
        val NETMIRROR_GATEWAYS = listOf(
            "https://netmirrors.vip",
            "https://net79.cc",
            "https://net52.cc"
        )
        const val CINEMETA_BASE = "https://v3-cinemeta.strem.io"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private var activeGateway = DEFAULT_DOMAIN
        private var lastGatewaySync = 0L
    }

    override var mainUrl = DEFAULT_DOMAIN
    override var name = "NetMirror"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    /**
     * Active gateway health check and auto-failover across the NetMirror cluster.
     */
    private suspend fun syncGateway() {
        if (System.currentTimeMillis() - lastGatewaySync < 1800000 && activeGateway != DEFAULT_DOMAIN) return

        for (gw in NETMIRROR_GATEWAYS) {
            try {
                val res = app.get("$gw/api/catalog/curated/trending", headers = mapOf("User-Agent" to USER_AGENT), timeout = 4)
                if (res.code == 200 && res.text.contains("ok")) {
                    activeGateway = gw
                    lastGatewaySync = System.currentTimeMillis()
                    return
                }
            } catch (_: Throwable) {
                // Failover to next candidate in cluster
            }
        }
    }

    // --- Native NetMirror Catalogs ---
    override val mainPage = mainPageOf(
        "curated:trending" to "🔥 Trending & Highlights",
        "curated:Netflix" to "Netflix Curated",
        "curated:PrimeVideo" to "Prime Video Curated",
        "curated:Crunchyroll" to "Crunchyroll Anime",
        "discover:JioHotstar:movie" to "JioHotstar Movies",
        "discover:JioHotstar:tv" to "JioHotstar Series",
        "discover:SonyLIV:movie" to "Sony LIV Movies",
        "discover:SonyLIV:tv" to "Sony LIV Series",
        "discover:Zee5:movie" to "Zee5 Movies",
        "discover:Zee5:tv" to "Zee5 Series",
        "discover:MX:movie" to "MX Player Movies",
        "discover:MX:tv" to "MX Player Series",
        "discover:Netflix:movie" to "Netflix All Movies",
        "discover:Netflix:tv" to "Netflix All Series",
        "discover:PrimeVideo:movie" to "Prime Video All Movies",
        "discover:PrimeVideo:tv" to "Prime Video All Series"
    )

    private fun NetMirrorItem.toSearchResponse(): SearchResponse? {
        val id = tmdbId ?: return null
        val t = title ?: return null
        val isTv = type.equals("tv", ignoreCase = true)
        val ctype = if (isTv) "series" else "movie"
        val payload = "netmirror:$ctype:$id:$t"
        val pUrl = poster
        return if (isTv) {
            newTvSeriesSearchResponse(t, payload, TvType.TvSeries) {
                this.posterUrl = pUrl
            }
        } else {
            newMovieSearchResponse(t, payload, TvType.Movie) {
                this.posterUrl = pUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncGateway()

        // 1. Curated Section (returns all rails as horizontal rows on page 1)
        if (request.data.startsWith("curated:")) {
            val curatedName = request.data.removePrefix("curated:")
            val url = "$activeGateway/api/catalog/curated/$curatedName"
            val res = app.get(
                url,
                headers = mapOf("Referer" to "$activeGateway/", "User-Agent" to USER_AGENT),
                timeout = 8
            ).parsedSafe<CuratedCatalogResponse>()

            val rails = res?.rails ?: emptyList()
            if (rails.isNotEmpty()) {
                val lists = rails.mapNotNull { rail ->
                    val items = rail.items?.mapNotNull { it.toSearchResponse() } ?: return@mapNotNull null
                    if (items.isEmpty()) return@mapNotNull null
                    HomePageList(
                        name = rail.title ?: request.name,
                        list = items,
                        isHorizontalImages = false
                    )
                }
                return newHomePageResponse(lists, hasNext = false)
            }
        }

        // 2. Discover Platform Section (paginated 20 items per page)
        if (request.data.startsWith("discover:")) {
            val parts = request.data.split(":")
            val platform = parts.getOrNull(1) ?: "Netflix"
            val ctype = parts.getOrNull(2) ?: "movie"
            val url = "$activeGateway/api/catalog/discover?platform=$platform&type=$ctype&sort=popularity&region=IN&page=$page"

            val res = app.get(
                url,
                headers = mapOf("Referer" to "$activeGateway/", "User-Agent" to USER_AGENT),
                timeout = 8
            ).parsedSafe<DiscoverResponse>()

            val items = res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            val totalPages = res?.totalPages ?: 1
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = page < totalPages
            )
        }

        return newHomePageResponse(emptyList(), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        syncGateway()
        val gateways = listOf(activeGateway) + NETMIRROR_GATEWAYS.filter { it != activeGateway }

        // Query NetMirror's native search API directly
        for (gw in gateways) {
            try {
                val url = "$gw/api/catalog/search?q=$encoded"
                val res = app.get(
                    url,
                    headers = mapOf("Referer" to "$gw/", "User-Agent" to USER_AGENT),
                    timeout = 6
                ).parsedSafe<NetMirrorSearchResponse>()

                val items = res?.items?.mapNotNull { it.toSearchResponse() }
                if (!items.isNullOrEmpty()) {
                    return items
                }
            } catch (_: Throwable) {}
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = when {
            url.contains("netmirror:") -> url.substringAfter("netmirror:")
            url.contains("meta:") -> url.substringAfter("meta:")
            else -> url
        }
        val parts = cleanUrl.split(":", limit = 4)
        val ctype = parts.getOrNull(0) ?: "movie"
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: parts.getOrNull(2)?.toIntOrNull() ?: 0
        val fallbackTitle = parts.lastOrNull() ?: ""

        syncGateway()
        val gateways = listOf(activeGateway) + NETMIRROR_GATEWAYS.filter { it != activeGateway }
        var embedInfo: NetMirrorEmbedResponse? = null

        if (tmdbId > 0) {
            for (gw in gateways) {
                try {
                    val apiUrl = "$gw/api/embed-tmdb/$tmdbId?type=$ctype&se=1&ep=1"
                    val res = app.get(
                        apiUrl,
                        headers = mapOf("Referer" to "$gw/", "User-Agent" to USER_AGENT),
                        timeout = 6
                    ).parsedSafe<NetMirrorEmbedResponse>()
                    if (res?.ok == true) {
                        embedInfo = res
                        break
                    }
                } catch (_: Throwable) {}
            }
        }

        val resolvedName = embedInfo?.title ?: fallbackTitle
        val resolvedYear = embedInfo?.year?.take(4)?.toIntOrNull()
        val resolvedPoster = embedInfo?.poster
        val imdbId = embedInfo?.imdb ?: ""
        val isTv = ctype.equals("series", ignoreCase = true) || ctype.equals("tv", ignoreCase = true)

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            if (imdbId.isNotBlank() && imdbId.startsWith("tt")) {
                try {
                    val cinemeta = app.get("$CINEMETA_BASE/meta/series/$imdbId.json", timeout = 6)
                        .parsedSafe<CinemetaDetailsResponse>()?.meta
                    if (!cinemeta?.videos.isNullOrEmpty()) {
                        cinemeta.videos.filter { (it.season ?: 0) > 0 }.forEach { v ->
                            val sNum = v.season ?: 1
                            val epNum = v.episode ?: v.number ?: 1
                            val epData = "stream:tv:$tmdbId:$sNum:$epNum:$resolvedName"
                            episodes.add(
                                newEpisode(epData) {
                                    this.name = v.title?.ifBlank { "Episode $epNum" } ?: "Episode $epNum"
                                    this.season = sNum
                                    this.episode = epNum
                                    this.posterUrl = v.thumbnail ?: resolvedPoster
                                    this.description = v.overview
                                }
                            )
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (episodes.isEmpty()) {
                for (ep in 1..25) {
                    val epData = "stream:tv:$tmdbId:1:$ep:$resolvedName"
                    episodes.add(
                        newEpisode(epData) {
                            this.name = "Episode $ep"
                            this.season = 1
                            this.episode = ep
                            this.posterUrl = resolvedPoster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(resolvedName, url, TvType.TvSeries, episodes) {
                this.posterUrl = resolvedPoster
                this.year = resolvedYear
                if (imdbId.isNotBlank()) addImdbId(imdbId)
            }
        }

        val movieData = "stream:movie:$tmdbId:1:1:$resolvedName"
        return newMovieLoadResponse(resolvedName, url, TvType.Movie, movieData) {
            this.posterUrl = resolvedPoster
            this.year = resolvedYear
            if (imdbId.isNotBlank()) addImdbId(imdbId)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.contains("stream:")) return false

        syncGateway()
        val cleanData = data.substringAfter("stream:")
        val parts = cleanData.split(":", limit = 5)
        val ctype = parts.getOrNull(0) ?: "movie"
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val season = parts.getOrNull(2)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(3)?.toIntOrNull() ?: 1

        if (tmdbId <= 0) return false

        val gateways = listOf(activeGateway) + NETMIRROR_GATEWAYS.filter { it != activeGateway }

        for (gw in gateways) {
            try {
                val apiUrl = "$gw/api/embed-tmdb/$tmdbId?type=$ctype&se=$season&ep=$episode"
                val res = app.get(
                    apiUrl,
                    headers = mapOf("Referer" to "$gw/", "User-Agent" to USER_AGENT),
                    timeout = 6
                ).parsedSafe<NetMirrorEmbedResponse>()

                if (res?.ok == true && !res.streams.isNullOrEmpty()) {
                    // 1. Add Direct MP4 CDN streams
                    res.streams.forEach { st ->
                        val stUrl = st.url ?: return@forEach
                        val resP = st.resolution ?: 720
                        val qual = when {
                            resP >= 1080 -> Qualities.P1080.value
                            resP >= 720 -> Qualities.P720.value
                            resP >= 480 -> Qualities.P480.value
                            else -> Qualities.P360.value
                        }

                        val cdnReferer = when {
                            stUrl.contains("hakunaymatata.com") -> "https://moviebox.ph/"
                            stUrl.contains("mxcontent.net") -> "https://mixdrop.top/"
                            stUrl.contains("messycloud.ink") -> "https://netmirror.hair/"
                            stUrl.contains("juicybits.io") -> "https://www.messycloud.ink/"
                            stUrl.contains("aoneroom.com") -> "https://videodownloader.site/"
                            else -> "$gw/"
                        }

                        callback(
                            ExtractorLink(
                                source = "NetMirror",
                                name = "NetMirror [${resP}p Direct]",
                                url = stUrl,
                                referer = cdnReferer,
                                quality = qual,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }

                    // 2. Add Subtitles
                    res.subtitles?.forEach { sub ->
                        val rawSubUrl = sub.url ?: return@forEach
                        val subUrl = if (rawSubUrl.startsWith("/")) "$gw$rawSubUrl" else rawSubUrl
                        val subName = sub.name ?: sub.lang ?: "Subtitle"
                        subtitleCallback(SubtitleFile(subName, subUrl))
                    }

                    return true
                }
            } catch (_: Throwable) {}
        }

        return false
    }

    // --- JSON Schema Mappings for NetMirror Native API ---

    data class CuratedCatalogResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("rails") val rails: List<CuratedRail>? = null
    )

    data class CuratedRail(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("items") val items: List<NetMirrorItem>? = null
    )

    data class DiscoverResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("items") val items: List<NetMirrorItem>? = null
    )

    data class NetMirrorSearchResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("items") val items: List<NetMirrorItem>? = null
    )

    data class NetMirrorItem(
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("overview") val overview: String? = null
    )

    data class CinemetaDetailsResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    data class CinemetaMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("background") val background: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("videos") val videos: List<CinemetaVideo>? = null
    )

    data class CinemetaVideo(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null
    )

    data class NetMirrorEmbedResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("imdb") val imdb: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("streams") val streams: List<NetMirrorStream>? = null,
        @JsonProperty("subtitles") val subtitles: List<NetMirrorSubtitle>? = null,
        @JsonProperty("noSource") val noSource: Boolean? = null,
        @JsonProperty("error") val error: String? = null
    )

    data class NetMirrorStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Int? = null,
        @JsonProperty("size") val size: Long? = null
    )

    data class NetMirrorSubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
