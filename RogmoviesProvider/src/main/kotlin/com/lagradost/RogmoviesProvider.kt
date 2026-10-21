package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class RogmoviesProvider : MainAPI() {
    companion object {
        const val MAIN_URL = "https://moviesnation.rent"
        const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = MAIN_URL
    override var name = "Rogmovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = listOf(
        MainPageData("Latest Releases", "$mainUrl/", true),
        MainPageData("Bollywood Movies", "$mainUrl/bollywood-movies/", true),
        MainPageData("South Hindi Dubbed", "$mainUrl/south-hindi-dubbed/", true),
        MainPageData("Hollywood Hindi Dubbed", "$mainUrl/hollywood-movies/", true),
        MainPageData("Netflix Series", "$mainUrl/netflix/", true),
        MainPageData("Amazon Prime Series", "$mainUrl/amazon-prime-video/", true),
        MainPageData("JioHotstar Series", "$mainUrl/disney-hotstar/", true),
        MainPageData("2160p 4K UHD", "$mainUrl/2160-4k/", true)
    )

    private fun getPageUrl(baseUrl: String, page: Int): String {
        return if (page <= 1) {
            baseUrl
        } else {
            val trimmed = baseUrl.removeSuffix("/")
            "$trimmed/page/$page/"
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    private fun parseCard(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a[href*='.html']") ?: element.selectFirst("a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = linkTag.attr("data-title").takeIf { it.isNotBlank() }
            ?: element.selectFirst(".poster-title, h2, h3, a[title]")?.text()
            ?: linkTag.text()

        if (title.isBlank() || href.contains("/page/")) return null

        val posterUrl = element.selectFirst("img.xfieldimage, img.featured-image, img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let { fixUrl(it) }
        }

        val quality = element.selectFirst(".poster-quality")?.text()?.trim()

        val isTvSeries = title.contains("Season", ignoreCase = true) ||
                title.contains("Series", ignoreCase = true) ||
                title.contains("Episode", ignoreCase = true) ||
                href.contains("season", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = getPageUrl(request.data, page)
        val response = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        }.getOrNull() ?: return newHomePageResponse(emptyList(), false)

        val doc = Jsoup.parse(response)
        val cards = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory")
        val items = cards.mapNotNull { parseCard(it) }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty() && doc.select(".navigation a:contains(Next), .navigation a:contains(>), .page-numbers").isNotEmpty()
        )
    }

    private suspend fun queryDleSearch(query: String): List<SearchResponse> {
        val response = runCatching {
            app.post(
                "$mainUrl/",
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
                    "Referer" to "$mainUrl/"
                )
            ).text
        }.getOrNull() ?: return emptyList()

        val doc = Jsoup.parse(response)
        val cards = doc.select(".movies-grid > a, .poster-card, a.wp-dle-generated-card, .shortstory, a[href*='.html']")
        return cards.mapNotNull { parseCard(it) }.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        val directResults = queryDleSearch(cleanQuery)
        if (directResults.isNotEmpty()) return directResults

        // Fallback: If 0 results, strip season/episode qualifiers to find the base series page
        val simplifiedQuery = cleanQuery.replace(Regex("""(?i)\b(?:season|series|episode|s\d+|e\d+)\b.*"""), "").trim()
        if (simplifiedQuery.isNotBlank() && simplifiedQuery != cleanQuery) {
            val fallbackResults = queryDleSearch(simplifiedQuery)
            if (fallbackResults.isNotEmpty()) return fallbackResults
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        }.getOrNull() ?: return null

        val doc = Jsoup.parse(response)
        val rawTitle = doc.selectFirst("h1, .entry-title, title")?.text()?.replace("Download", "", ignoreCase = true)?.trim() ?: return null
        val cleanTitle = rawTitle.replace(Regex("""(?i)\b(?:480p|720p|1080p|2160p|4k|WEB-DL|HEVC|Hindi|DD5\.1).*$"""), "").trim()

        val poster = doc.selectFirst("img.xfieldimage, .entry-content img, .poster-image img")?.let {
            val src = it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
            src?.let { s -> fixUrl(s) }
        }

        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val synopsis = doc.selectFirst(".story-content, .entry-content p, .synopsis")?.text()

        // Extract IMDb ID and query TMDB for metadata & 16:9 high-res backdrop
        val imdbId = doc.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.let {
            Regex("""(tt\d+)""").find(it)?.groupValues?.get(1)
        } ?: Regex("""\b(tt\d{7,8})\b""").find(doc.html())?.groupValues?.get(1)

        var tmdbBackdrop: String? = null
        var tmdbPlot: String? = null

        if (!imdbId.isNullOrBlank()) {
            runCatching {
                val tmdbUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
                val find = app.get(tmdbUrl, timeout = 3).parsedSafe<TmdbFindResponse>()
                val item = find?.movieResults?.firstOrNull() ?: find?.tvResults?.firstOrNull()
                if (item != null) {
                    item.backdropPath?.let { tmdbBackdrop = "https://image.tmdb.org/t/p/original$it" }
                    tmdbPlot = item.overview
                }
            }
        }

        val finalPlot = tmdbPlot?.ifBlank { null } ?: synopsis
        val finalBackdrop = tmdbBackdrop ?: poster

        val isTvSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("Series", ignoreCase = true) ||
                url.contains("season", ignoreCase = true)

        val bollyLinks = doc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")
        val archiveLinks = doc.select("a[href*='links.bollydrive.buzz/archives/'], a[href*='links.bollydrive.mom/archives/']")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            if (bollyLinks.isNotEmpty()) {
                bollyLinks.forEachIndexed { idx, aTag ->
                    val epHref = aTag.attr("href").replace("bollydrive.mom", "bollydrive.buzz")
                    val epText = aTag.text().trim()
                    val epNum = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                    val seasonNum = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epText
                            this.episode = epNum
                            this.season = seasonNum
                            this.posterUrl = poster
                        }
                    )
                }
            } else if (archiveLinks.isNotEmpty()) {
                val archUrl = archiveLinks.firstOrNull()?.attr("href")?.trim()
                val archHtml = if (!archUrl.isNullOrEmpty()) {
                    runCatching {
                        app.get(archUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).text
                    }.getOrNull()
                } else null
                if (archHtml != null) {
                    val archDoc = Jsoup.parse(archHtml)
                    val epFiles = archDoc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")
                    epFiles.forEachIndexed { idx, aTag ->
                        val epHref = aTag.attr("href").replace("bollydrive.mom", "bollydrive.buzz")
                        val epText = aTag.text().trim().ifBlank { "Episode ${idx + 1}" }
                        val epNum = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epText
                                this.episode = epNum
                                this.season = 1
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = finalBackdrop
                this.year = year
                this.plot = finalPlot
                if (!imdbId.isNullOrBlank()) addImdbId(imdbId)
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = finalBackdrop
                this.year = year
                this.plot = finalPlot
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
        // If data is directly a Bollydrive file link:
        if (data.contains("bollydrive.buzz") || data.contains("bollydrive.mom")) {
            val cleanLink = data.replace("bollydrive.mom", "bollydrive.buzz")
            loadExtractor(cleanLink, subtitleCallback, callback)
            return true
        }

        // If data is a Movie page URL, parse all Bollydrive links on the page:
        val response = runCatching {
            app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).text
        }.getOrNull() ?: return false

        val doc = Jsoup.parse(response)
        val bollyLinks = doc.select("a[href*='bollydrive.buzz/file/'], a[href*='bollydrive.mom/file/']")
            .map { it.attr("href").trim().replace("bollydrive.mom", "bollydrive.buzz") }
            .distinct()

        coroutineScope {
            bollyLinks.map { link ->
                async {
                    runCatching {
                        loadExtractor(link, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }

        return true
    }

    data class TmdbFindResponse(
        @JsonProperty("movie_results") val movieResults: List<TmdbItem>? = null,
        @JsonProperty("tv_results") val tvResults: List<TmdbItem>? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )
}
