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
        const val STREMIO_CATALOG_BASE =
            "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxqaHMsemVlLHNvbnlsaXYsY3J1OjpJTjoxNzkyMzQzMDk5OTQ1OjA6MDo%3D/catalog"
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

    override val mainPage = mainPageOf(
        "stremio:series:jhs" to "JioHotstar: Series",
        "stremio:movie:jhs" to "JioHotstar: Movies",
        "stremio:series:nfx" to "Netflix: Series",
        "stremio:movie:nfx" to "Netflix: Movies",
        "stremio:series:amp" to "Prime Video: Series",
        "stremio:movie:amp" to "Prime Video: Movies",
        "stremio:series:sonyliv" to "Sony LIV: Series",
        "stremio:movie:sonyliv" to "Sony LIV: Movies",
        "stremio:movie:zee" to "Zee5: Movies",
        "curated:trending" to "Trending Across OTT"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("stremio:")) {
            val parts = request.data.split(":")
            val ctype = parts.getOrNull(1) ?: "series"
            val cid = parts.getOrNull(2) ?: "jhs"

            val skipParam = if (page > 1) "/skip=${(page - 1) * 100}" else ""
            val url = "$STREMIO_CATALOG_BASE/$ctype/$cid$skipParam.json"

            val res = app.get(url, timeout = 10).parsedSafe<StremioCatalogResponse>()
            val metas = res?.metas ?: emptyList()

            val items = metas.map { meta ->
                val title = meta.name ?: "Unknown"
                val poster = meta.poster
                val imdbId = meta.id ?: ""
                val payload = "meta:$ctype:$imdbId:${meta.moviedbId ?: 0}:$title"

                if (ctype == "series") {
                    newTvSeriesSearchResponse(title, payload, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, payload, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = items.size >= 50
            )
        }

        // NetMirror Curated Trending API
        syncGateway()
        val curUrl = "$activeGateway/api/catalog/curated/trending"
        val curatedRes = app.get(curUrl, headers = mapOf("Referer" to "$activeGateway/", "User-Agent" to USER_AGENT), timeout = 8)
            .parsedSafe<CuratedCatalogResponse>()

        val rails = curatedRes?.rails ?: emptyList()
        val allItems = mutableListOf<SearchResponse>()

        for (r in rails) {
            val railItems = r.items ?: continue
            for (it in railItems) {
                val title = it.title ?: continue
                val tmdbId = it.tmdbId ?: 0
                val isTv = it.type == "tv"
                val ctype = if (isTv) "series" else "movie"
                val payload = "meta:$ctype::$tmdbId:$title"
                val poster = it.poster

                val item = if (isTv) {
                    newTvSeriesSearchResponse(title, payload, TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                } else {
                    newMovieSearchResponse(title, payload, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
                allItems.add(item)
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = allItems.distinctBy { it.url },
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        try {
            val seriesRes = app.get("$CINEMETA_BASE/catalog/series/top/search=$encoded.json", timeout = 6)
                .parsedSafe<CinemetaCatalogResponse>()
            seriesRes?.metas?.forEach { m ->
                val title = m.name ?: return@forEach
                val id = m.id ?: return@forEach
                val payload = "meta:series:$id:${m.moviedbId ?: 0}:$title"
                results.add(
                    newTvSeriesSearchResponse(title, payload, TvType.TvSeries) {
                        this.posterUrl = m.poster
                    }
                )
            }
        } catch (_: Throwable) {}

        try {
            val movieRes = app.get("$CINEMETA_BASE/catalog/movie/top/search=$encoded.json", timeout = 6)
                .parsedSafe<CinemetaCatalogResponse>()
            movieRes?.metas?.forEach { m ->
                val title = m.name ?: return@forEach
                val id = m.id ?: return@forEach
                val payload = "meta:movie:$id:${m.moviedbId ?: 0}:$title"
                results.add(
                    newMovieSearchResponse(title, payload, TvType.Movie) {
                        this.posterUrl = m.poster
                    }
                )
            }
        } catch (_: Throwable) {}

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.split(":", limit = 5)
        val ctype = parts.getOrNull(1) ?: "movie"
        val imdbId = parts.getOrNull(2) ?: ""
        var tmdbId = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val title = parts.getOrNull(4) ?: ""

        // 1. Resolve metadata from Cinemeta
        var resolvedName = title
        var resolvedPlot: String? = null
        var resolvedPoster: String? = null
        var resolvedBackdrop: String? = null
        var resolvedYear: Int? = null
        val episodes = mutableListOf<Episode>()

        if (imdbId.isNotBlank()) {
            try {
                val metaUrl = "$CINEMETA_BASE/meta/$ctype/$imdbId.json"
                val cinemeta = app.get(metaUrl, timeout = 7).parsedSafe<CinemetaDetailsResponse>()?.meta
                if (cinemeta != null) {
                    resolvedName = cinemeta.name ?: resolvedName
                    resolvedPlot = cinemeta.description
                    resolvedPoster = cinemeta.poster
                    resolvedBackdrop = cinemeta.background ?: cinemeta.poster
                    resolvedYear = cinemeta.year?.take(4)?.toIntOrNull()
                    if (tmdbId <= 0 && cinemeta.moviedbId != null) {
                        tmdbId = cinemeta.moviedbId
                    }

                    if (ctype == "series" && !cinemeta.videos.isNullOrEmpty()) {
                        cinemeta.videos.filter { (it.season ?: 0) > 0 }.forEach { v ->
                            val sNum = v.season ?: 1
                            val epNum = v.episode ?: v.number ?: 1
                            val epData = "stream:tv:$tmdbId:$sNum:$epNum:$imdbId:$resolvedName"
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
                }
            } catch (_: Throwable) {}
        }

        if (ctype == "series" && episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(resolvedName, url, TvType.TvSeries, episodes) {
                this.posterUrl = resolvedPoster
                this.backgroundPosterUrl = resolvedBackdrop
                this.plot = resolvedPlot
                this.year = resolvedYear
                if (imdbId.isNotBlank()) addImdbId(imdbId)
            }
        }

        val movieData = "stream:movie:$tmdbId:1:1:$imdbId:$resolvedName"
        return newMovieLoadResponse(resolvedName, url, TvType.Movie, movieData) {
            this.posterUrl = resolvedPoster
            this.backgroundPosterUrl = resolvedBackdrop
            this.plot = resolvedPlot
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
        if (!data.startsWith("stream:")) return false

        syncGateway()
        val parts = data.split(":", limit = 7)
        val ctype = parts.getOrNull(1) ?: "movie"
        var tmdbId = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val season = parts.getOrNull(3)?.toIntOrNull() ?: 1
        val episode = parts.getOrNull(4)?.toIntOrNull() ?: 1
        val imdbId = parts.getOrNull(5) ?: ""
        val title = parts.getOrNull(6) ?: ""

        // If tmdbId was missing, resolve it via Cinemeta
        if (tmdbId <= 0 && imdbId.isNotBlank()) {
            try {
                val cinemeta = app.get("$CINEMETA_BASE/meta/$ctype/$imdbId.json", timeout = 5)
                    .parsedSafe<CinemetaDetailsResponse>()?.meta
                if (cinemeta?.moviedbId != null) {
                    tmdbId = cinemeta.moviedbId
                }
            } catch (_: Throwable) {}
        }

        if (tmdbId <= 0) return false

        val gateways = listOf(activeGateway) + NETMIRROR_GATEWAYS.filter { it != activeGateway }
        for (gw in gateways) {
            try {
                val apiUrl = "$gw/api/embed-tmdb/$tmdbId?type=$ctype&se=$season&ep=$episode"
                val res = app.get(apiUrl, headers = mapOf("Referer" to "$gw/", "User-Agent" to USER_AGENT), timeout = 6)
                    .parsedSafe<NetMirrorEmbedResponse>()

                if (res?.ok == true && !res.streams.isNullOrEmpty()) {
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
                    return true
                }
            } catch (_: Throwable) {
                // Try next gateway in cluster
            }
        }

        return false
    }

    // --- JSON Schema Mappings ---

    data class StremioCatalogResponse(
        @JsonProperty("metas") val metas: List<StremioMeta>? = null
    )

    data class StremioMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("moviedb_id") val moviedbId: Int? = null
    )

    data class CuratedCatalogResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("rails") val rails: List<CuratedRail>? = null
    )

    data class CuratedRail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("items") val items: List<CuratedItem>? = null
    )

    data class CuratedItem(
        @JsonProperty("tmdbId") val tmdbId: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("rating") val rating: Double? = null
    )

    data class CinemetaCatalogResponse(
        @JsonProperty("metas") val metas: List<CinemetaMeta>? = null
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
        @JsonProperty("moviedb_id") val moviedbId: Int? = null,
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
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("streams") val streams: List<NetMirrorStream>? = null
    )

    data class NetMirrorStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Int? = null,
        @JsonProperty("size") val size: Long? = null
    )
}
