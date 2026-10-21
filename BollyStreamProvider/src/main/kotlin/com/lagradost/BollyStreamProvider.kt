package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class BollyStreamProvider : MainAPI() {
    companion object {
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val TMDB_IMG_POSTER = "https://image.tmdb.org/t/p/w500"
        const val TMDB_IMG_BACKDROP = "https://image.tmdb.org/t/p/original"
        const val CATALOG_URL = "https://moviesnation.rent"
        const val HDHUB4U_URL = "https://new8.hdhub4u.cl"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = TMDB_API
    override var name = "BollyStream"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = listOf(
        MainPageData("Bollywood Trending", "$mainUrl/discover/movie?with_original_language=hi&sort_by=popularity.desc", true),
        MainPageData("Indian Web Series", "$mainUrl/discover/tv?with_original_language=hi&sort_by=popularity.desc", true),
        MainPageData("South Indian Hits", "$mainUrl/discover/movie?with_original_language=te|ta|ml|kn&sort_by=popularity.desc", true),
        MainPageData("Global Trending Movies", "$mainUrl/trending/movie/day", true),
        MainPageData("Global Trending Series", "$mainUrl/trending/tv/day", true)
    )

    private fun parseTmdbItem(item: TmdbItem): SearchResponse? {
        val id = item.id ?: return null
        val isTv = item.mediaType == "tv" || item.title == null
        val title = item.title ?: item.name ?: return null
        val poster = item.posterPath?.let { "$TMDB_IMG_POSTER$it" }
        val route = if (isTv) "tv/$id" else "movie/$id"

        return if (isTv) {
            newTvSeriesSearchResponse(title, route, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, route, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}api_key=$TMDB_API_KEY&page=$page"

        val response = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<TmdbPageResponse>()
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)

        val items = response.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val totalPages = response.totalPages ?: 1

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = page < totalPages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val url = "$mainUrl/search/multi?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(cleanQuery, "UTF-8")}&include_adult=false"

        val response = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<TmdbPageResponse>()
        }.getOrNull() ?: return emptyList()

        return response.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.startsWith("tv/") || url.contains("/tv/")
        val idStr = url.substringAfterLast("/")
        val id = idStr.toIntOrNull() ?: return null

        val detailUrl = if (isTv) {
            "$mainUrl/tv/$id?api_key=$TMDB_API_KEY&append_to_response=external_ids"
        } else {
            "$mainUrl/movie/$id?api_key=$TMDB_API_KEY&append_to_response=external_ids"
        }

        val detail = runCatching {
            app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<TmdbDetail>()
        }.getOrNull() ?: return null

        val title = detail.title ?: detail.name ?: return null
        val poster = detail.posterPath?.let { "$TMDB_IMG_POSTER$it" }
        val backdrop = detail.backdropPath?.let { "$TMDB_IMG_BACKDROP$it" } ?: poster
        val releaseDate = detail.releaseDate ?: detail.firstAirDate
        val year = releaseDate?.substringBefore("-")?.toIntOrNull()
        val imdbId = detail.externalIds?.imdbId
        val tags = detail.genres?.mapNotNull { it.name }

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            val seasons = detail.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()

            coroutineScope {
                seasons.map { season ->
                    async {
                        val sNum = season.seasonNumber ?: 1
                        val sUrl = "$mainUrl/tv/$id/season/$sNum?api_key=$TMDB_API_KEY"
                        val seasonDetail = runCatching {
                            app.get(sUrl, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<TmdbSeasonDetail>()
                        }.getOrNull()

                        seasonDetail?.episodes?.forEach { ep ->
                            val epNum = ep.episodeNumber ?: 1
                            val epName = ep.name ?: "Episode $epNum"
                            val epThumb = ep.stillPath?.let { "$TMDB_IMG_POSTER$it" } ?: poster
                            val linkPayload = BollyStreamLinkData(
                                title = title,
                                year = year,
                                imdbId = imdbId,
                                tmdbId = id,
                                isMovie = false,
                                season = sNum,
                                episode = epNum
                            ).toJson()

                            synchronized(episodes) {
                                episodes.add(
                                    newEpisode(linkPayload) {
                                        this.name = epName
                                        this.season = sNum
                                        this.episode = epNum
                                        this.posterUrl = epThumb
                                        this.description = ep.overview
                                    }
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            episodes.sortBy { (it.season ?: 0) * 1000 + (it.episode ?: 0) }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = detail.overview
                this.tags = tags
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        } else {
            val linkPayload = BollyStreamLinkData(
                title = title,
                year = year,
                imdbId = imdbId,
                tmdbId = id,
                isMovie = true
            ).toJson()

            newMovieLoadResponse(title, url, TvType.Movie, linkPayload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = detail.overview
                this.tags = tags
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { parseJson<BollyStreamLinkData>(data) }.getOrNull()

        val title = payload?.title ?: return false
        val year = payload.year
        val isMovie = payload.isMovie
        val season = payload.season ?: 1
        val episode = payload.episode ?: 1

        coroutineScope {
            // ── Scraper 1: MoviesNation / Rogmovies (Bollydrive 10Gbps CDN) ─────────
            val job1 = async {
                scrapeMoviesNation(title, year, isMovie, season, episode, subtitleCallback, callback)
            }

            // ── Scraper 2: HDhub4u (HubCloud CDN) ───────────────────────────────────
            val job2 = async {
                scrapeHDhub4u(title, payload.imdbId, isMovie, season, episode, subtitleCallback, callback)
            }

            job1.await()
            job2.await()
        }

        return true
    }

    private suspend fun scrapeMoviesNation(
        title: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val query = if (isMovie) title else "$title Season $season"
            var searchRes = app.post(
                "$CATALOG_URL/",
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "search_start" to "0",
                    "full_search" to "0",
                    "story" to query
                ),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to "$CATALOG_URL/"
                )
            ).text

            var doc = Jsoup.parse(searchRes)
            var articleLinks = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory a[href*='.html']")
                .map { it.attr("href") }
                .filter { it.contains(".html") && !it.contains("/page/") }
                .distinct()

            if (articleLinks.isEmpty() && !isMovie) {
                // Fallback search with base title
                searchRes = app.post(
                    "$CATALOG_URL/",
                    data = mapOf(
                        "do" to "search",
                        "subaction" to "search",
                        "search_start" to "0",
                        "full_search" to "0",
                        "story" to title
                    ),
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to "$CATALOG_URL/"
                    )
                ).text
                doc = Jsoup.parse(searchRes)
                articleLinks = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory a[href*='.html']")
                    .map { it.attr("href") }
                    .filter { it.contains(".html") && !it.contains("/page/") }
                    .distinct()
            }

            val targetUrl = articleLinks.firstOrNull() ?: return@runCatching
            val articleHtml = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            val articleDoc = Jsoup.parse(articleHtml)

            val bollyLinks = articleDoc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")
            val archiveLinks = articleDoc.select("a[href*='links.bollydrive.buzz/archives/'], a[href*='links.bollydrive.mom/archives/']")

            if (isMovie) {
                bollyLinks.forEach { a ->
                    val href = a.attr("href").trim().replace("bollydrive.mom", "bollydrive.buzz")
                    loadExtractor(href, subtitleCallback, callback)
                }
            } else {
                val epRegex = Regex("""(?:Episode|EP|E)\s*0?$episode\b""", RegexOption.IGNORE_CASE)
                val directMatch = bollyLinks.filter { a -> epRegex.containsMatchIn(a.text()) }

                if (directMatch.isNotEmpty()) {
                    directMatch.forEach { a ->
                        val href = a.attr("href").trim().replace("bollydrive.mom", "bollydrive.buzz")
                        loadExtractor(href, subtitleCallback, callback)
                    }
                } else if (archiveLinks.isNotEmpty()) {
                    archiveLinks.take(2).forEach { arch ->
                        val archUrl = arch.attr("href").trim()
                        val archHtml = runCatching {
                            app.get(archUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
                        }.getOrNull() ?: return@forEach

                        val archDoc = Jsoup.parse(archHtml)
                        val epAnchors = archDoc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")

                        var targetFile = epAnchors.firstOrNull { a -> epRegex.containsMatchIn(a.text()) }?.attr("href")

                        if (targetFile.isNullOrBlank() && epAnchors.size >= episode) {
                            targetFile = epAnchors[episode - 1].attr("href")
                        }

                        if (!targetFile.isNullOrBlank()) {
                            val cleanHref = targetFile.trim().replace("bollydrive.mom", "bollydrive.buzz")
                            loadExtractor(cleanHref, subtitleCallback, callback)
                        }
                    }
                }
            }
        }.onFailure { e ->
            Log.e("BollyStream", "MoviesNation scraper error: ${e.message}")
        }
    }

    private suspend fun scrapeHDhub4u(
        title: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val query = if (isMovie) (imdbId ?: title) else "$title Season $season"
            val searchUrl = "$HDHUB4U_URL/?s=${URLEncoder.encode(query, "UTF-8")}"
            val searchHtml = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
            val doc = Jsoup.parse(searchHtml)

            val articleLink = doc.selectFirst(".recent-movies a[href], article a[href], h2 a[href]")?.attr("href") ?: return@runCatching
            val pageHtml = app.get(articleLink, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
            val pageDoc = Jsoup.parse(pageHtml)

            if (isMovie) {
                pageDoc.select("a[href*='hubcloud'], a[href*='hubstream'], a[href*='hdstream4u'], a[href*='suncdn']").forEach { a ->
                    val href = a.attr("href").trim()
                    if (href.startsWith("http")) {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                }
            } else {
                val epRegex = Regex("""(?:Episode|EP|E)\s*0?$episode\b""", RegexOption.IGNORE_CASE)
                pageDoc.select("a[href*='hubcloud'], a[href*='hubstream'], a[href*='hdstream4u'], a[href*='suncdn']").forEach { a ->
                    val text = a.text()
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && epRegex.containsMatchIn(text)) {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                }
            }
        }.onFailure { e ->
            Log.e("BollyStream", "HDhub4u scraper error: ${e.message}")
        }
    }

    data class BollyStreamLinkData(
        val title: String,
        val year: Int? = null,
        val imdbId: String? = null,
        val tmdbId: Int? = null,
        val isMovie: Boolean = true,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class TmdbPageResponse(
        @JsonProperty("results") val results: List<TmdbItem>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class TmdbDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null
    )

    data class TmdbSeason(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
        @JsonProperty("poster_path") val posterPath: String? = null
    )

    data class TmdbSeasonDetail(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
    )

    data class TmdbEpisode(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("air_date") val airDate: String? = null
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String? = null
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )
}
