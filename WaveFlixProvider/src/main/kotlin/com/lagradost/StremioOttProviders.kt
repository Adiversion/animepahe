package com.lagradost

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

open class StremioOttProvider(
    override var name: String,
    val providerId: String,
    val jwPackage: String = providerId,
    val defaultType: TvType = TvType.TvSeries,
    val customMainPage: List<MainPageData>? = null
) : MainAPI() {
    override var mainUrl = "https://www.waveflix.online"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true

    override val mainPage: List<MainPageData> = customMainPage ?: if (defaultType == TvType.Anime) {
        mainPageOf(
            "SHOW:15:0" to "Trending Anime Series",
            "MOVIE:15:0" to "Anime Movies"
        )
    } else {
        mainPageOf(
            "SHOW:15:0" to "Popular Series",
            "MOVIE:15:0" to "Popular Movies"
        )
    }

    companion object {
        private val ottCache = mutableMapOf<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 mins cache
        private val sharedEngine = WaveFlixProvider()
        private val idCache = ConcurrentHashMap<String, Long>()
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Content-Type" to "application/json"
    )

    private fun getUserCountry(): String {
        return try {
            val country = Locale.getDefault().country
            if (!country.isNullOrBlank() && country.length == 2) country.uppercase() else "IN"
        } catch (_: Throwable) {
            "IN"
        }
    }

    private suspend fun resolveTmdbId(id: String?, isMovie: Boolean, name: String?): Long? {
        if (id.isNullOrBlank()) return null
        if (id.toLongOrNull() != null) return id.toLong()

        idCache[id]?.let { return it }

        // 1. If it's an IMDb ID (e.g. tt11663228), query Cinemeta
        if (id.startsWith("tt")) {
            val cType = if (isMovie) "movie" else "series"
            val cinRes = runCatching {
                app.get("https://v3-cinemeta.strem.io/meta/$cType/$id.json", timeout = 3)
                    .parsedSafe<CinemetaResponse>()
            }.getOrNull()
            val foundId = cinRes?.meta?.moviedbId
            if (foundId != null && foundId > 0) {
                idCache[id] = foundId
                return foundId
            }
        }

        // 2. Fallback: Search WaveFlix internal search API by title
        if (!name.isNullOrBlank()) {
            val found = runCatching {
                val enc = URLEncoder.encode(name, "UTF-8")
                val searchRes = app.get("https://www.waveflix.online/api/search?q=$enc", timeout = 3)
                    .parsedSafe<WaveFlixProvider.WaveSearchResponse>()
                searchRes?.results?.firstOrNull()?.id
            }.getOrNull()
            if (found != null && found > 0) {
                idCache[id] = found
                return found
            }
        }

        return null
    }

    private suspend fun fetchJustWatchCatalog(
        pkg: String,
        isMovie: Boolean,
        country: String,
        count: Int
    ): List<SearchResponse> {
        return try {
            val gql = """
                query GetPopularTitles(${"$"}country: Country!, ${"$"}language: Language!, ${"$"}first: Int!, ${"$"}filter: TitleFilter) {
                  popularTitles(country: ${"$"}country, first: ${"$"}first, filter: ${"$"}filter) {
                    edges {
                      node {
                        objectType
                        content(country: ${"$"}country, language: ${"$"}language) {
                          title
                          originalReleaseYear
                          posterUrl
                          scoring {
                            imdbScore
                          }
                          externalIds {
                            imdbId
                            tmdbId
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val payloadMap = mapOf(
                "operationName" to "GetPopularTitles",
                "query" to gql,
                "variables" to mapOf(
                    "country" to country,
                    "language" to "en",
                    "first" to count,
                    "filter" to mapOf(
                        "packages" to listOf(pkg),
                        "objectTypes" to listOf(if (isMovie) "MOVIE" else "SHOW"),
                        "monetizationTypes" to listOf("FLATRATE")
                    )
                )
            )

            val res = app.post(
                "https://apis.justwatch.com/graphql",
                headers = commonHeaders,
                json = payloadMap,
                timeout = 6
            ).parsedSafe<JwGraphQLResponse>()

            val edges = res?.data?.popularTitles?.edges ?: return emptyList()

            coroutineScope {
                edges.map { edge ->
                    async {
                        val node = edge.node ?: return@async null
                        val content = node.content ?: return@async null
                        val title = content.title ?: return@async null

                        val rawTmdb = content.externalIds?.tmdbId?.toLongOrNull()
                        val imdbId = content.externalIds?.imdbId

                        val tmdbId = rawTmdb ?: resolveTmdbId(imdbId, isMovie, title) ?: return@async null
                        val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        val poster = content.posterUrl?.let {
                            if (it.startsWith("/")) {
                                "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg")
                            } else it
                        }

                        if (isMovie) {
                            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        } else {
                            newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = getUserCountry()
        val cacheKey = "$name/$country/${request.data}/${request.name}/$page"
        val now = System.currentTimeMillis()

        synchronized(ottCache) {
            val cached = ottCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        // Format of request.data: "SHOW:10:0" -> type, count, offset
        val parts = request.data.split(":")
        val isMovie = parts.getOrNull(0)?.equals("MOVIE", ignoreCase = true) == true
        val count = parts.getOrNull(1)?.toIntOrNull() ?: 15
        val offset = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val fetchCount = (count + offset).coerceAtLeast(10)

        val allItems = fetchJustWatchCatalog(
            pkg = jwPackage,
            isMovie = isMovie,
            country = country,
            count = fetchCount
        )

        val items = if (offset > 0 && allItems.size > offset) {
            allItems.drop(offset).take(count)
        } else {
            allItems.take(count)
        }

        val response = newHomePageResponse(request.name, items, hasNext = false)
        synchronized(ottCache) {
            ottCache[cacheKey] = now to response
        }
        return response
    }

    private fun normalizeTokens(str: String): List<String> {
        return str.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length > 1 }
            .map { w ->
                if (w.length > 3 && w.endsWith("es")) w.dropLast(2)
                else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1)
                else w
            }
    }

    private fun isRelevantTitle(query: String, title: String): Boolean {
        val qTokens = normalizeTokens(query)
        if (qTokens.isEmpty()) return true
        val tTokens = normalizeTokens(title)
        if (tTokens.isEmpty()) return false

        if (qTokens.size == 1) {
            val q = qTokens[0]
            return tTokens.any { it.contains(q) || q.contains(it) }
        }

        val cleanQ = query.trim().lowercase()
        val cleanT = title.trim().lowercase()
        if (cleanT.contains(cleanQ)) return true

        val matches = qTokens.count { q ->
            tTokens.any { t -> t.contains(q) || q.contains(t) }
        }

        if (qTokens.size == 2) {
            return matches == 2
        }

        return (matches.toDouble() / qTokens.size) >= 0.6
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val country = getUserCountry()
        return searchJustWatch(cleanQuery, jwPackage, country)
    }

    private suspend fun searchJustWatch(query: String, pkgCode: String, country: String): List<SearchResponse> {
        return try {
            val gql = """
                query GetSuggestedTitles(${"$"}country: Country!, ${"$"}language: Language!, ${"$"}first: Int!, ${"$"}filter: TitleFilter) {
                  popularTitles(country: ${"$"}country, first: ${"$"}first, filter: ${"$"}filter) {
                    edges {
                      node {
                        id
                        objectType
                        objectId
                        content(country: ${"$"}country, language: ${"$"}language) {
                          title
                          originalReleaseYear
                          posterUrl
                          scoring {
                            imdbScore
                          }
                          externalIds {
                            imdbId
                            tmdbId
                          }
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val payloadMap = mapOf(
                "operationName" to "GetSuggestedTitles",
                "query" to gql,
                "variables" to mapOf(
                    "country" to country,
                    "language" to "en",
                    "first" to 20,
                    "filter" to mapOf(
                        "searchQuery" to query,
                        "packages" to listOf(pkgCode)
                    )
                )
            )

            val res = app.post(
                "https://apis.justwatch.com/graphql",
                headers = commonHeaders,
                json = payloadMap,
                timeout = 8
            ).parsedSafe<JwGraphQLResponse>()

            val edges = res?.data?.popularTitles?.edges ?: return emptyList()
            coroutineScope {
                edges.map { edge ->
                    async {
                        val node = edge.node ?: return@async null
                        val content = node.content ?: return@async null
                        val title = content.title ?: return@async null

                        // Strict relevance check to prevent irrelevant matches on other platforms
                        if (!isRelevantTitle(query, title)) return@async null

                        val isMovie = node.objectType?.equals("MOVIE", ignoreCase = true) == true
                        val rawTmdb = content.externalIds?.tmdbId?.toLongOrNull()
                        val imdbId = content.externalIds?.imdbId

                        val tmdbId = rawTmdb ?: resolveTmdbId(imdbId, isMovie, title) ?: return@async null
                        val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        val poster = content.posterUrl?.let {
                            if (it.startsWith("/")) {
                                "https://images.justwatch.com" + it.replace("{profile}", "s332").replace("{format}", "jpg")
                            } else it
                        }

                        if (isMovie) {
                            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        } else {
                            newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = content.originalReleaseYear
                            }
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.url }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val res = sharedEngine.load(url)
        res.apiName = this.name
        return res
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return sharedEngine.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwGraphQLResponse(
        @JsonProperty("data") val data: JwData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwData(
        @JsonProperty("popularTitles") val popularTitles: JwPopularTitles? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwPopularTitles(
        @JsonProperty("totalCount") val totalCount: Int? = null,
        @JsonProperty("edges") val edges: List<JwEdge>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwEdge(
        @JsonProperty("node") val node: JwNode? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwNode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("objectType") val objectType: String? = null,
        @JsonProperty("objectId") val objectId: Long? = null,
        @JsonProperty("content") val content: JwContent? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwContent(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalReleaseYear") val originalReleaseYear: Int? = null,
        @JsonProperty("posterUrl") val posterUrl: String? = null,
        @JsonProperty("shortDescription") val shortDescription: String? = null,
        @JsonProperty("scoring") val scoring: JwScoring? = null,
        @JsonProperty("externalIds") val externalIds: JwExternalIds? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwScoring(
        @JsonProperty("imdbScore") val imdbScore: Double? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class JwExternalIds(
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CinemetaMeta(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("moviedb_id") val moviedbId: Long? = null,
        @JsonProperty("year") val year: String? = null
    )
}

class NetflixProvider : StremioOttProvider(
    name = "Netflix",
    providerId = "nfx",
    jwPackage = "nfx",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on Netflix Today",
        "MOVIE:10:0" to "Top 10 Movies on Netflix Today",
        "SHOW:20:10" to "Popular Series on Netflix",
        "MOVIE:20:10" to "Popular Movies on Netflix"
    )
)

class JioHotstarProvider : StremioOttProvider(
    name = "JioHotstar",
    providerId = "jhs",
    jwPackage = "jhs",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on JioHotstar Today",
        "MOVIE:10:0" to "Top 10 Movies on JioHotstar Today",
        "SHOW:20:10" to "Popular Shows on JioHotstar",
        "MOVIE:20:10" to "Popular Movies on JioHotstar"
    )
)

class PrimeVideoProvider : StremioOttProvider(
    name = "Amazon Prime",
    providerId = "prv",
    jwPackage = "prv",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on Prime Video Today",
        "MOVIE:10:0" to "Top 10 Movies on Prime Video Today",
        "SHOW:20:10" to "Popular Series on Prime Video",
        "MOVIE:20:10" to "Popular Movies on Prime Video"
    )
)

class Zee5Provider : StremioOttProvider(
    name = "Zee5",
    providerId = "zee",
    jwPackage = "zee",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on Zee5 Today",
        "MOVIE:10:0" to "Top 10 Movies on Zee5 Today",
        "SHOW:20:10" to "Popular Shows on Zee5",
        "MOVIE:20:10" to "Popular Movies on Zee5"
    )
)

class SonyLivProvider : StremioOttProvider(
    name = "SonyLIV",
    providerId = "snl",
    jwPackage = "snl",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on SonyLIV Today",
        "MOVIE:10:0" to "Top 10 Movies on SonyLIV Today",
        "SHOW:20:10" to "Popular Shows on SonyLIV",
        "MOVIE:20:10" to "Popular Movies on SonyLIV"
    )
)

class AppleTvProvider : StremioOttProvider(
    name = "Apple TV+",
    providerId = "atp",
    jwPackage = "atp",
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Shows on Apple TV+ Today",
        "MOVIE:10:0" to "Top 10 Movies on Apple TV+ Today",
        "SHOW:20:10" to "Popular Shows on Apple TV+",
        "MOVIE:20:10" to "Popular Movies on Apple TV+"
    )
)

class CrunchyrollProvider : StremioOttProvider(
    name = "Crunchyroll",
    providerId = "cru",
    jwPackage = "cru",
    defaultType = TvType.Anime,
    customMainPage = mainPageOf(
        "SHOW:10:0" to "Top 10 Anime Series Today",
        "MOVIE:10:0" to "Top 10 Anime Movies Today",
        "SHOW:20:10" to "Popular Anime Series",
        "MOVIE:20:10" to "Popular Anime Movies"
    )
)
