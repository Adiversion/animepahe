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
            "catalog/series/{country}_en_jw_pop_$providerId" to "Trending Anime Series",
            "catalog/movie/{country}_en_jw_pop_$providerId" to "Anime Movies"
        )
    } else {
        mainPageOf(
            "catalog/series/{country}_en_jw_pop_$providerId" to "Popular Series",
            "catalog/movie/{country}_en_jw_pop_$providerId" to "Popular Movies"
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
        "Referer" to "https://5cfe2edf73d5-omnicatalogs.baby-beamup.club/"
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
                app.get("https://v3-cinemeta.strem.io/meta/$cType/$id.json", timeout = 4)
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
                val searchRes = app.get("https://www.waveflix.online/api/search?q=$enc", timeout = 4)
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = getUserCountry()
        val cleanPath = request.data.replace("{country}", country)
        val cacheKey = "$name/$country/$cleanPath/${request.name}/$page"
        val now = System.currentTimeMillis()

        synchronized(ottCache) {
            val cached = ottCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val config = "${country}_en_nfx_prv_jhs_zee_snl_atp_cru"
        val catalogUrl = "https://5cfe2edf73d5-omnicatalogs.baby-beamup.club/$config/$cleanPath.json"

        val res = app.get(catalogUrl, headers = commonHeaders, timeout = 10)
            .parsedSafe<StremioCatalogResponse>() ?: throw ErrorLoadingException("Failed to load $name catalog")

        val rawMetas = res.metas ?: emptyList()
        val isTop10 = request.name.contains("Top 10", ignoreCase = true)
        val candidateMetas = if (isTop10) {
            rawMetas.take(10)
        } else if (request.name.contains("Popular", ignoreCase = true) && rawMetas.size > 10) {
            rawMetas.drop(10).take(20).ifEmpty { rawMetas.take(20) }
        } else {
            rawMetas.take(20)
        }

        val items = coroutineScope {
            candidateMetas.map { meta ->
                async {
                    val title = meta.name ?: return@async null
                    val isMovie = meta.type?.lowercase() == "movie"
                    val tmdbId = meta.moviedbId ?: resolveTmdbId(meta.id, isMovie, title) ?: return@async null
                    val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"
                    val posterUrl = meta.poster

                    if (isMovie) {
                        newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.year = meta.releaseInfo?.take(4)?.toIntOrNull() ?: meta.year?.take(4)?.toIntOrNull()
                        }
                    } else {
                        newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.year = meta.releaseInfo?.take(4)?.toIntOrNull() ?: meta.year?.take(4)?.toIntOrNull()
                        }
                    }
                }
            }.awaitAll().filterNotNull()
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

        // 1. First, search the entire full database of this streaming platform via JustWatch's public GraphQL
        val jwResults = searchJustWatch(cleanQuery, jwPackage, country)
        if (jwResults.isNotEmpty()) {
            return jwResults
        }

        // 2. Fallback to Stremio local catalog search if JustWatch returns no matches
        return searchStremioCatalog(cleanQuery, country)
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
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json"
                ),
                json = payloadMap,
                timeout = 8
            ).parsedSafe<JwGraphQLResponse>()

            val edges = res?.data?.popularTitles?.edges ?: return emptyList()
            edges.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                val content = node.content ?: return@mapNotNull null
                val title = content.title ?: return@mapNotNull null

                // Strict relevance check to prevent irrelevant matches on other platforms
                if (!isRelevantTitle(query, title)) return@mapNotNull null

                val isMovie = node.objectType?.equals("MOVIE", ignoreCase = true) == true
                val rawTmdb = content.externalIds?.tmdbId?.toLongOrNull()
                val imdbId = content.externalIds?.imdbId

                val tmdbId = rawTmdb ?: if (!imdbId.isNullOrBlank()) {
                    runCatching {
                        val cType = if (isMovie) "movie" else "series"
                        val cinRes = app.get("https://v3-cinemeta.strem.io/meta/$cType/$imdbId.json", timeout = 4)
                            .parsedSafe<CinemetaResponse>()
                        cinRes?.meta?.moviedbId
                    }.getOrNull()
                } else null

                val targetUrl = if (tmdbId != null && tmdbId > 0) {
                    "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"
                } else {
                    return@mapNotNull null
                }

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
            }.distinctBy { it.url }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun searchStremioCatalog(query: String, country: String): List<SearchResponse> {
        val cleanQuery = query.lowercase()
        return try {
            val config = "${country}_en_nfx_prv_jhs_zee_snl_atp_cru"
            val seriesUrl = "https://5cfe2edf73d5-omnicatalogs.baby-beamup.club/$config/catalog/series/${country}_en_jw_pop_$providerId.json"
            val movieUrl = "https://5cfe2edf73d5-omnicatalogs.baby-beamup.club/$config/catalog/movie/${country}_en_jw_pop_$providerId.json"

            val seriesMetas = runCatching {
                app.get(seriesUrl, headers = commonHeaders, timeout = 6).parsedSafe<StremioCatalogResponse>()?.metas
            }.getOrNull() ?: emptyList()

            val movieMetas = runCatching {
                app.get(movieUrl, headers = commonHeaders, timeout = 6).parsedSafe<StremioCatalogResponse>()?.metas
            }.getOrNull() ?: emptyList()

            val matches = (seriesMetas + movieMetas).filter { meta ->
                val name = meta.name ?: return@filter false
                isRelevantTitle(query, name)
            }

            coroutineScope {
                matches.map { meta ->
                    async {
                        val title = meta.name ?: return@async null
                        val isMovie = meta.type?.lowercase() == "movie"
                        val tmdbId = meta.moviedbId ?: resolveTmdbId(meta.id, isMovie, title) ?: return@async null
                        val targetUrl = "https://www.waveflix.online/details/${if (isMovie) "movie" else "tv"}/$tmdbId"

                        if (isMovie) {
                            newMovieSearchResponse(title, targetUrl, TvType.Movie) {
                                this.posterUrl = meta.poster
                                this.year = meta.releaseInfo?.take(4)?.toIntOrNull() ?: meta.year?.take(4)?.toIntOrNull()
                            }
                        } else {
                            newTvSeriesSearchResponse(title, targetUrl, if (defaultType == TvType.Anime) TvType.Anime else TvType.TvSeries) {
                                this.posterUrl = meta.poster
                                this.year = meta.releaseInfo?.take(4)?.toIntOrNull() ?: meta.year?.take(4)?.toIntOrNull()
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }.distinctBy { it.url }
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
    data class StremioCatalogResponse(
        @JsonProperty("metas") val metas: List<StremioMetaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StremioMetaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("moviedb_id") val moviedbId: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseInfo") val releaseInfo: String? = null,
        @JsonProperty("year") val year: String? = null
    )

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
        "catalog/movie/{country}_en_jw_tnd_nfx" to "Top 10 Movies on Netflix Today",
        "catalog/series/{country}_en_jw_tnd_nfx" to "Top 10 Shows on Netflix Today",
        "catalog/movie/{country}_en_jw_pop_nfx" to "Popular Movies on Netflix",
        "catalog/series/{country}_en_jw_pop_nfx" to "Popular Series on Netflix",
        "catalog/movie/{country}_en_jw_new_nfx" to "New on Netflix",
        "catalog/series/{country}_en_jw_new_nfx" to "New Series on Netflix"
    )
)

class JioHotstarProvider : StremioOttProvider(
    name = "JioHotstar",
    providerId = "jhs",
    jwPackage = "jhs",
    customMainPage = mainPageOf(
        "catalog/series/{country}_en_jw_tnd_jhs" to "Top 10 Shows on JioHotstar Today",
        "catalog/movie/{country}_en_jw_tnd_jhs" to "Top 10 Movies on JioHotstar Today",
        "catalog/series/{country}_en_jw_pop_jhs" to "Popular Shows on JioHotstar",
        "catalog/movie/{country}_en_jw_pop_jhs" to "Popular Movies on JioHotstar",
        "catalog/series/{country}_en_jw_new_jhs" to "New on JioHotstar",
        "catalog/movie/{country}_en_jw_new_jhs" to "New Movies on JioHotstar"
    )
)

class PrimeVideoProvider : StremioOttProvider(
    name = "Amazon Prime",
    providerId = "prv",
    jwPackage = "prv",
    customMainPage = mainPageOf(
        "catalog/movie/{country}_en_jw_tnd_prv" to "Top 10 Movies on Prime Video Today",
        "catalog/series/{country}_en_jw_tnd_prv" to "Top 10 Shows on Prime Video Today",
        "catalog/movie/{country}_en_jw_pop_prv" to "Popular Movies on Prime Video",
        "catalog/series/{country}_en_jw_pop_prv" to "Popular Series on Prime Video",
        "catalog/movie/{country}_en_jw_new_prv" to "New Movies on Prime Video",
        "catalog/series/{country}_en_jw_new_prv" to "New Series on Prime Video"
    )
)

class Zee5Provider : StremioOttProvider(
    name = "Zee5",
    providerId = "zee",
    jwPackage = "zee",
    customMainPage = mainPageOf(
        "catalog/movie/{country}_en_jw_tnd_zee" to "Top 10 Movies on Zee5 Today",
        "catalog/series/{country}_en_jw_tnd_zee" to "Top 10 Shows on Zee5 Today",
        "catalog/movie/{country}_en_jw_pop_zee" to "Popular Movies on Zee5",
        "catalog/series/{country}_en_jw_pop_zee" to "Popular Shows on Zee5",
        "catalog/movie/{country}_en_jw_new_zee" to "New on Zee5"
    )
)

class SonyLivProvider : StremioOttProvider(
    name = "SonyLIV",
    providerId = "snl",
    jwPackage = "snl",
    customMainPage = mainPageOf(
        "catalog/movie/{country}_en_jw_tnd_snl" to "Top 10 Movies on SonyLIV Today",
        "catalog/series/{country}_en_jw_tnd_snl" to "Top 10 Shows on SonyLIV Today",
        "catalog/movie/{country}_en_jw_pop_snl" to "Popular Movies on SonyLIV",
        "catalog/series/{country}_en_jw_pop_snl" to "Popular Shows on SonyLIV",
        "catalog/movie/{country}_en_jw_new_snl" to "New on SonyLIV"
    )
)

class AppleTvProvider : StremioOttProvider(
    name = "Apple TV+",
    providerId = "atp",
    jwPackage = "atp",
    customMainPage = mainPageOf(
        "catalog/movie/{country}_en_jw_tnd_atp" to "Top 10 Movies on Apple TV+ Today",
        "catalog/series/{country}_en_jw_tnd_atp" to "Top 10 Shows on Apple TV+ Today",
        "catalog/movie/{country}_en_jw_pop_atp" to "Popular Movies on Apple TV+",
        "catalog/series/{country}_en_jw_pop_atp" to "Popular Shows on Apple TV+",
        "catalog/movie/{country}_en_jw_new_atp" to "New Movies on Apple TV+",
        "catalog/series/{country}_en_jw_new_atp" to "New Series on Apple TV+"
    )
)

class CrunchyrollProvider : StremioOttProvider(
    name = "Crunchyroll",
    providerId = "cru",
    jwPackage = "cru",
    defaultType = TvType.Anime,
    customMainPage = mainPageOf(
        "catalog/series/{country}_en_jw_tnd_cru" to "Top 10 Anime Series Today",
        "catalog/movie/{country}_en_jw_tnd_cru" to "Top 10 Anime Movies Today",
        "catalog/series/{country}_en_jw_pop_cru" to "Popular Anime Series",
        "catalog/movie/{country}_en_jw_pop_cru" to "Popular Anime Movies",
        "catalog/series/{country}_en_jw_new_cru" to "New Anime Series"
    )
)
