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
        const val TMDB_API = "https://api.tmdb.org/3"
        const val TMDB_API_BACKUP = "https://api.themoviedb.org/3"
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

    private suspend inline fun <reified T : Any> getTmdb(endpoint: String): T? {
        val hosts = listOf(TMDB_API, TMDB_API_BACKUP)
        for (host in hosts) {
            val separator = if (endpoint.contains("?")) "&" else "?"
            val fullUrl = if (endpoint.startsWith("http")) endpoint else "$host/$endpoint${separator}api_key=$TMDB_API_KEY"
            val res = runCatching {
                app.get(fullUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 4).parsedSafe<T>()
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

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
        val relPath = request.data.removePrefix(TMDB_API).removePrefix(TMDB_API_BACKUP).removePrefix("/")
        val separator = if (relPath.contains("?")) "&" else "?"
        val pathWithPage = "$relPath${separator}page=$page"

        val response = getTmdb<TmdbPageResponse>(pathWithPage)
            ?: return newHomePageResponse(emptyList(), false)

        val items = response.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val totalPages = response.totalPages ?: 1

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = page < totalPages
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val path = "search/multi?query=${URLEncoder.encode(cleanQuery, "UTF-8")}&include_adult=false"
        val response = getTmdb<TmdbPageResponse>(path)

        val items = response?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        if (items.isNotEmpty()) return items

        return searchHDhub4uFallback(cleanQuery)
    }

    private suspend fun searchHDhub4uFallback(query: String): List<SearchResponse> {
        return runCatching {
            val searchUrl = "$HDHUB4U_URL/?s=${URLEncoder.encode(query, "UTF-8")}"
            val html = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
            val doc = Jsoup.parse(html)
            doc.select(".movies-grid > a, .poster-card, article.post").mapNotNull { card ->
                val link = card.selectFirst("a")?.attr("href") ?: card.attr("href")
                val title = card.selectFirst("h2, .title, a")?.text()?.trim() ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")
                if (link.isNotBlank()) {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    }
                } else null
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("http")) {
            return runCatching {
                val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
                val doc = Jsoup.parse(html)
                val title = doc.title().trim().substringBefore("–").substringBefore("-").trim()
                val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }.getOrNull()
        }

        val isTv = url.startsWith("tv/") || url.contains("/tv/")
        val idStr = url.substringAfterLast("/")
        val id = idStr.toIntOrNull() ?: return null

        val path = if (isTv) "tv/$id?append_to_response=external_ids" else "movie/$id?append_to_response=external_ids"
        val detail = getTmdb<TmdbDetail>(path) ?: return null

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
                        val sPath = "tv/$id/season/$sNum"
                        val seasonDetail = getTmdb<TmdbSeasonDetail>(sPath)

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
        if (data.startsWith("http")) {
            val res = runCatching {
                val doc = Jsoup.parse(app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).text)
                doc.select("a[href*='bollydrive.'], a[href*='nexdrive.'], a[href*='vcloud.'], a[href*='fastdl.'], a[href*='gofile.']").forEach { a ->
                    loadExtractor(a.attr("href").trim(), subtitleCallback, callback)
                }
            }
            return res.isSuccess
        }

        val payload = runCatching { parseJson<BollyStreamLinkData>(data) }.getOrNull()

        val title = payload?.title ?: return false
        val year = payload.year
        val isMovie = payload.isMovie
        val season = payload.season ?: 1
        val episode = payload.episode ?: 1

        val engineSources: List<IMirrorSource> = listOf(
            MoviesNationSource(),
            HDhub4uSource()
        )

        coroutineScope {
            engineSources.map { source ->
                async {
                    runCatching {
                        source.resolve(title, year, payload.imdbId, isMovie, season, episode, subtitleCallback, callback)
                    }.onFailure { e ->
                        Log.e("MirrorEngine", "${source.name} error: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-PROVIDER BACKEND ENGINE (MirrorEngine)
    // ═══════════════════════════════════════════════════════════════════════════

    interface IMirrorSource {
        val name: String
        suspend fun resolve(
            title: String,
            year: Int?,
            imdbId: String?,
            isMovie: Boolean,
            season: Int,
            episode: Int,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        )
    }

    /**
     * Backend 1: MoviesNation / Rogmovies
     * Resolves: Bollydrive (Cloudflare R2 Direct + 10Gbps Google CDN), Nexdrive, V-Cloud.
     */
    inner class MoviesNationSource : IMirrorSource {
        override val name = "MoviesNation"

        override suspend fun resolve(
            title: String,
            year: Int?,
            imdbId: String?,
            isMovie: Boolean,
            season: Int,
            episode: Int,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val query = if (isMovie && year != null) "$title $year" else if (isMovie) title else "$title Season $season"
            var searchRes = runCatching {
                app.post(
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
            }.getOrNull() ?: return

            var doc = Jsoup.parse(searchRes)
            var articleLinks = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory a[href*='.html']")
                .map { it.attr("href") }
                .filter { it.contains(".html") && !it.contains("/page/") }
                .distinct()

            if (articleLinks.isEmpty()) {
                val fbRes = runCatching {
                    app.post(
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
                }.getOrNull()
                if (fbRes != null) {
                    doc = Jsoup.parse(fbRes)
                    articleLinks = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory a[href*='.html']")
                        .map { it.attr("href") }
                        .filter { it.contains(".html") && !it.contains("/page/") }
                        .distinct()
                }
            }

            val targetUrl = articleLinks.firstOrNull() ?: return
            val articleHtml = runCatching {
                app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            }.getOrNull() ?: return
            val articleDoc = Jsoup.parse(articleHtml)

            val bollyLinks = articleDoc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")
            val nexdriveLinks = articleDoc.select("a[href*='nexdrive.beer/']")
            val vcloudLinks = articleDoc.select("a[href*='vcloud.beer/'], a[href*='vcloud.lol/'], a[href*='fastcloud.lol/']")
            val archiveLinks = articleDoc.select("a[href*='links.bollydrive.buzz/archives/'], a[href*='links.bollydrive.mom/archives/']")

            if (isMovie) {
                bollyLinks.forEach { a ->
                    val href = a.attr("href").trim().replace("bollydrive.mom", "bollydrive.buzz")
                    loadExtractor(href, subtitleCallback, callback)
                }
                nexdriveLinks.forEach { a ->
                    loadExtractor(a.attr("href").trim(), subtitleCallback, callback)
                }
                vcloudLinks.forEach { a ->
                    loadExtractor(a.attr("href").trim(), subtitleCallback, callback)
                }
            } else {
                val epRegex = Regex("""(?:Episode|EP|E)\s*0?$episode\b""", RegexOption.IGNORE_CASE)
                val directMatch = bollyLinks.filter { a -> epRegex.containsMatchIn(a.text()) }

                if (directMatch.isNotEmpty()) {
                    directMatch.forEach { a ->
                        val href = a.attr("href").trim().replace("bollydrive.mom", "bollydrive.buzz")
                        loadExtractor(href, subtitleCallback, callback)
                    }
                } else if (nexdriveLinks.isNotEmpty()) {
                    val targetNex = nexdriveLinks.firstOrNull { it.text().contains("1080p") || it.text().contains("720p") } ?: nexdriveLinks.firstOrNull()
                    val nexUrl = targetNex?.attr("href")?.trim() ?: ""
                    val nexHtml = if (nexUrl.isNotEmpty()) {
                        runCatching {
                            app.get(nexUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
                        }.getOrNull()
                    } else null

                    if (nexHtml != null) {
                        val nexDoc = Jsoup.parse(nexHtml)
                        val vclouds = nexDoc.select("a[href*='vcloud.']")
                        val epTarget = vclouds.getOrNull(episode - 1)
                        if (epTarget != null) {
                            loadExtractor(epTarget.attr("href").trim(), subtitleCallback, callback)
                        }
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
        }
    }

    /**
     * Backend 2: HDhub4u
     * Resolves: HubDrive, HubCloud (Cloudflare R2 Direct + 10Gbps PixelServer + BuzzServer), HDStream4u HLS, Hubstream.
     */
    inner class HDhub4uSource : IMirrorSource {
        override val name = "HDhub4u"

        override suspend fun resolve(
            title: String,
            year: Int?,
            imdbId: String?,
            isMovie: Boolean,
            season: Int,
            episode: Int,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val query = if (isMovie && year != null) "$title $year" else title

            val postUrl = runCatching {
                val pingoraUrl = "https://search.pingora.fyi/collections/post/documents/search?q=${URLEncoder.encode(query, "UTF-8")}&query_by=post_title,category,stars,director,imdb_id&query_by_weights=4,2,2,2,4&sort_by=sort_by_date:desc&limit=5&highlight_fields=none&use_cache=true&page=1"
                val res = app.get(
                    pingoraUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$HDHUB4U_URL/",
                        "Origin" to HDHUB4U_URL,
                        "Accept" to "application/json, text/plain, */*"
                    ),
                    timeout = 8
                ).parsedSafe<PingoraSearchResponse>()

                val permalink = res?.hits?.firstOrNull()?.document?.permalink
                permalink?.replace(Regex("""https?://[^/]+"""), HDHUB4U_URL)
            }.getOrNull() ?: runCatching {
                val searchUrl = "$HDHUB4U_URL/?s=${URLEncoder.encode(query, "UTF-8")}"
                val searchHtml = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
                val doc = Jsoup.parse(searchHtml)
                doc.selectFirst(".recent-movies a[href], article a[href], h2 a[href]")?.attr("href")
            }.getOrNull() ?: return

            val pageHtml = runCatching {
                app.get(postUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 8).text
            }.getOrNull() ?: return
            val pageDoc = Jsoup.parse(pageHtml)

            val linksToLoad = mutableListOf<String>()

            if (isMovie) {
                pageDoc.select("a[href*='hubdrive.'], a[href*='hubcloud.'], a[href*='hdstream4u.'], a[href*='hubstream.'], a[href*='suncdn.']").forEach { a ->
                    val href = a.attr("href").trim()
                    if (href.startsWith("http")) linksToLoad.add(href)
                }
            } else {
                val epRegex = Regex("""(?:Episode|EP|E)\s*0?$episode\b""", RegexOption.IGNORE_CASE)
                pageDoc.select("a[href*='hubdrive.'], a[href*='hubcloud.'], a[href*='hdstream4u.'], a[href*='hubstream.'], a[href*='suncdn.']").forEach { a ->
                    val text = a.text()
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && epRegex.containsMatchIn(text)) {
                        linksToLoad.add(href)
                    }
                }
            }

            linksToLoad.distinct().forEach { href ->
                loadExtractor(href, subtitleCallback, callback)
            }
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

    data class PingoraSearchResponse(
        @JsonProperty("hits") val hits: List<PingoraHit>? = null,
        @JsonProperty("found") val found: Int? = null
    )

    data class PingoraHit(
        @JsonProperty("document") val document: PingoraDoc? = null
    )

    data class PingoraDoc(
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null,
        @JsonProperty("post_thumbnail") val postThumbnail: String? = null
    )
}
