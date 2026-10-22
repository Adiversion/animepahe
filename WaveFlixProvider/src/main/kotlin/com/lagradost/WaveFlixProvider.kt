package com.lagradost

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

class WaveFlixProvider : MainAPI() {
    override var name = "WaveFlix"
    override var mainUrl = "https://www.waveflix.online"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "movie/trending" to "Trending Movies",
        "movie/now_playing" to "Now Playing Movies",
        "movie/top_rated" to "Top Rated Movies",
        "tv/trending" to "Trending TV Shows",
        "tv/on_the_air" to "TV Shows On The Air",
        "anime/trending" to "Anime"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("/")
        val mediaType = parts.getOrNull(0) ?: "movie"
        val category = parts.getOrNull(1) ?: "trending"

        val endpoint = if (mediaType == "anime") {
            "$mainUrl/api/discover?mediaType=anime&page=$page"
        } else {
            "$mainUrl/api/discover?mediaType=$mediaType&category=$category&page=$page"
        }

        val res = app.get(endpoint, headers = commonHeaders)
            .parsedSafe<WaveSearchResponse>() ?: throw ErrorLoadingException("Failed to fetch WaveFlix discover catalog")

        val homeItems = res.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()

        val hasNext = page < (res.totalPages ?: 1)
        return newHomePageResponse(request.name, homeItems, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search?q=${query.encodeUri()}"
        val res = app.get(searchUrl, headers = commonHeaders)
            .parsedSafe<WaveSearchResponse>() ?: return emptyList()

        return res.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val isMovie = url.contains("/movie/")
        val idMatch = Regex("""/(?:details|watch)/(?:movie|tv)/(\d+)""").find(url)
        val tmdbId = idMatch?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("Invalid WaveFlix media ID from URL: $url")

        // Fetch page HTML to retrieve canonical title, synopsis, and poster
        val doc = app.get(url, headers = commonHeaders).document
        val rawTitle = doc.selectFirst("meta[property=og:image:alt]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1")?.text()
            ?: "Media #$tmdbId"
        
        val cleanTitle = rawTitle.removePrefix("Watch ").replace(Regex("""\s*\(\d{4}\).*"""), "").trim()
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: "https://image.tmdb.org/t/p/w500"

        val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        if (isMovie) {
            val linkData = WaveLinkData(
                tmdbId = tmdbId,
                isMovie = true
            )
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, toJson(linkData)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()
            var seasonNum = 1
            val maxSeasons = 30 // Prevent runaway loop

            while (seasonNum <= maxSeasons) {
                val seasonUrl = "$mainUrl/api/season?tvId=$tmdbId&season=$seasonNum"
                val seasonRes = try {
                    app.get(seasonUrl, headers = commonHeaders).parsedSafe<WaveSeasonResponse>()
                } catch (e: Exception) {
                    null
                }

                val epList = seasonRes?.episodes
                if (epList.isNullOrEmpty()) {
                    break
                }

                epList.forEach { ep ->
                    val epData = WaveLinkData(
                        tmdbId = tmdbId,
                        isMovie = false,
                        season = seasonNum,
                        episode = ep.episodeNumber
                    )

                    val epPoster = if (!ep.stillPath.isNullOrBlank()) {
                        "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                    } else poster

                    episodes.add(
                        newEpisode(toJson(epData)) {
                            this.name = ep.name ?: "Episode ${ep.episodeNumber}"
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.posterUrl = epPoster
                            this.description = ep.overview
                        }
                    )
                }

                seasonNum++
            }

            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val linkData = parseJson<WaveLinkData>(data)
        val tmdbId = linkData.tmdbId
        val isMovie = linkData.isMovie
        val season = linkData.season ?: 1
        val episode = linkData.episode ?: 1

        // 1. Build all embed provider endpoints supported by WaveFlix
        val servers = mutableListOf<Pair<String, String>>()

        // Nxsha embed (primary WaveFlix engine)
        val nxshaUrl = if (isMovie) {
            "https://nxsha.space/embed/movie/$tmdbId"
        } else {
            "https://nxsha.space/embed/tv/$tmdbId/$season/$episode"
        }
        servers.add("Nxsha" to nxshaUrl)

        // ScreenScape embed
        val screenscapeUrl = if (isMovie) {
            "https://screenscape.me/embed?tmdb=$tmdbId&type=movie"
        } else {
            "https://screenscape.me/embed?tmdb=$tmdbId&type=tv&s=$season&e=$episode"
        }
        servers.add("ScreenScape" to screenscapeUrl)

        // Peachify embed
        val peachifyUrl = if (isMovie) {
            "https://peachify.pro/embed/movie/$tmdbId"
        } else {
            "https://peachify.pro/embed/tv/$tmdbId/$season/$episode"
        }
        servers.add("Peachify" to peachifyUrl)

        // High-reliability universal mirror fallbacks
        val vidsrcUrl = if (isMovie) {
            "https://vidsrc.to/embed/movie/$tmdbId"
        } else {
            "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
        }
        servers.add("VidSrc" to vidsrcUrl)

        val autoembedUrl = if (isMovie) {
            "https://player.autoembed.cc/embed/movie/$tmdbId"
        } else {
            "https://player.autoembed.cc/embed/tv/$tmdbId/$season/$episode"
        }
        servers.add("AutoEmbed" to autoembedUrl)

        // 2. Query all servers asynchronously in parallel
        val jobs = servers.map { (serverName, embedUrl) ->
            async {
                try {
                    // Try built-in CloudStream extractor directly
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)

                    // Also inspect embed HTML for iframes and direct m3u8 streams
                    val res = app.get(
                        embedUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to "$mainUrl/"
                        ),
                        timeout = 10
                    )
                    val html = res.text

                    // Check for nested iframes
                    val iframeMatches = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)
                    for (match in iframeMatches) {
                        val iframeSrc = match.groupValues[1]
                        val fullIframeUrl = if (iframeSrc.startsWith("//")) {
                            "https:$iframeSrc"
                        } else if (iframeSrc.startsWith("/")) {
                            val baseHost = embedUrl.substringBefore("/", "").ifEmpty { embedUrl }
                            "$baseHost$iframeSrc"
                        } else {
                            iframeSrc
                        }
                        try {
                            loadExtractor(fullIframeUrl, embedUrl, subtitleCallback, callback)
                        } catch (_: Throwable) {}
                    }

                    // Check for direct m3u8 playlists
                    val m3u8Matches = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html)
                    for (m3u8 in m3u8Matches) {
                        val streamUrl = m3u8.value
                        callback(
                            ExtractorLink(
                                source = serverName,
                                name = "$serverName Auto HLS",
                                url = streamUrl,
                                referer = embedUrl,
                                quality = Qualities.P1080.value,
                                isM3u8 = true
                            )
                        )
                    }
                } catch (_: Throwable) {
                    // Fail gracefully per server
                }
            }
        }

        jobs.forEach { it.await() }
        true
    }

    private fun WaveMediaItem.toSearchResponse(): SearchResponse? {
        val idVal = this.id ?: return null
        val isMovie = (this.mediaType?.lowercase() == "movie")
        val titleVal = this.title ?: this.name ?: return null
        val poster = if (!this.posterPath.isNullOrBlank()) {
            if (this.posterPath.startsWith("http")) this.posterPath else "https://image.tmdb.org/t/p/w500${this.posterPath}"
        } else null

        val targetUrl = "$mainUrl/details/${if (isMovie) "movie" else "tv"}/$idVal"

        return if (isMovie) {
            newMovieSearchResponse(titleVal, targetUrl, TvType.Movie) {
                this.posterUrl = poster
                this.rating = this@toSearchResponse.rating?.times(10)?.toInt()
            }
        } else {
            newTvSeriesSearchResponse(titleVal, targetUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.rating = this@toSearchResponse.rating?.times(10)?.toInt()
            }
        }
    }

    // JSON DTOs
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveSearchResponse(
        @JsonProperty("results") val results: List<WaveMediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("page") val page: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveMediaItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("mediaType") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveSeasonResponse(
        @JsonProperty("episodes") val episodes: List<WaveEpisodeItem>? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WaveEpisodeItem(
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class WaveLinkData(
        val tmdbId: Long,
        val isMovie: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )
}
