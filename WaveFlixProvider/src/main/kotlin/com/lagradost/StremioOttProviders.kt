package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.util.Locale

open class StremioOttProvider(
    override var name: String,
    val providerId: String,
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
            "catalog/series/$providerId" to "Trending Anime Series",
            "catalog/movie/$providerId" to "Anime Movies"
        )
    } else {
        mainPageOf(
            "catalog/series/$providerId" to "Popular Series",
            "catalog/movie/$providerId" to "Popular Movies"
        )
    }

    companion object {
        private val ottCache = mutableMapOf<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 mins cache
        private val sharedEngine = WaveFlixProvider()
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/"
    )

    private fun getUserCountry(): String {
        return try {
            val country = Locale.getDefault().country
            if (!country.isNullOrBlank() && country.length == 2) country.uppercase() else "IN"
        } catch (_: Throwable) {
            "IN"
        }
    }

    private fun getCatalogToken(country: String): String {
        // Enable Top 10 flags (global + country)
        val raw = "dnp,amp,atp,hbm,nfx,zee,jhs,sonyliv,cru::$country:1792722613240:1:1:$country"
        val b64 = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return URLEncoder.encode(b64, "UTF-8")
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

        val token = getCatalogToken(country)
        val catalogUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/$token/$cleanPath.json"

        val res = app.get(catalogUrl, headers = commonHeaders, timeout = 10)
            .parsedSafe<StremioCatalogResponse>() ?: throw ErrorLoadingException("Failed to load $name catalog")

        val items = res.metas?.mapNotNull { meta ->
            val tmdbId = meta.moviedbId ?: return@mapNotNull null
            val title = meta.name ?: return@mapNotNull null
            val isMovie = meta.type?.lowercase() == "movie"
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
        } ?: emptyList()

        val isTop10 = request.name.contains("Top 10", ignoreCase = true)
        val finalItems = if (isTop10) {
            items.take(10)
        } else if (request.name.contains("Popular", ignoreCase = true) && items.size > 10) {
            items.drop(10).ifEmpty { items }
        } else {
            items
        }

        val response = newHomePageResponse(request.name, finalItems, hasNext = false)
        synchronized(ottCache) {
            ottCache[cacheKey] = now to response
        }
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()

        // Query provider catalog directly so titles exclusive to other platforms (e.g. Gandhari) do not leak
        return try {
            val country = getUserCountry()
            val token = getCatalogToken(country)
            val seriesUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/$token/catalog/series/$providerId.json"
            val movieUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/$token/catalog/movie/$providerId.json"

            val seriesMetas = runCatching {
                app.get(seriesUrl, headers = commonHeaders, timeout = 6).parsedSafe<StremioCatalogResponse>()?.metas
            }.getOrNull() ?: emptyList()

            val movieMetas = runCatching {
                app.get(movieUrl, headers = commonHeaders, timeout = 6).parsedSafe<StremioCatalogResponse>()?.metas
            }.getOrNull() ?: emptyList()

            (seriesMetas + movieMetas).filter {
                it.name?.lowercase()?.contains(cleanQuery) == true
            }.mapNotNull { meta ->
                val tmdbId = meta.moviedbId ?: return@mapNotNull null
                val title = meta.name ?: return@mapNotNull null
                val isMovie = meta.type?.lowercase() == "movie"
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
            }.distinctBy { it.url }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return sharedEngine.load(url)
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
}

class NetflixProvider : StremioOttProvider(
    name = "Netflix",
    providerId = "nfx",
    customMainPage = mainPageOf(
        "catalog/movie/netflix-top10-{country}" to "Top 10 Movies on Netflix Today",
        "catalog/series/netflix-top10-{country}" to "Top 10 Shows on Netflix Today",
        "catalog/movie/nfx" to "Popular Movies on Netflix",
        "catalog/series/nfx" to "Popular Series on Netflix",
        "catalog/movie/netflix-top10-global" to "Global Top 10 Movies",
        "catalog/series/netflix-top10-global" to "Global Top 10 Shows"
    )
)
class JioHotstarProvider : StremioOttProvider(
    name = "JioHotstar",
    providerId = "jhs",
    customMainPage = mainPageOf(
        "catalog/movie/jhs" to "Top 10 Movies on JioHotstar Today",
        "catalog/series/jhs" to "Top 10 Shows on JioHotstar Today",
        "catalog/movie/jhs" to "Popular Movies on JioHotstar",
        "catalog/series/jhs" to "Popular Shows on JioHotstar"
    )
)
class PrimeVideoProvider : StremioOttProvider(
    name = "Amazon Prime",
    providerId = "amp",
    customMainPage = mainPageOf(
        "catalog/movie/amp" to "Top 10 Movies on Prime Video Today",
        "catalog/series/amp" to "Top 10 Shows on Prime Video Today",
        "catalog/movie/amp" to "Popular Movies on Prime Video",
        "catalog/series/amp" to "Popular Series on Prime Video"
    )
)
class Zee5Provider : StremioOttProvider(
    name = "Zee5",
    providerId = "zee",
    customMainPage = mainPageOf(
        "catalog/movie/zee" to "Top 10 Movies on Zee5 Today",
        "catalog/series/zee" to "Top 10 Shows on Zee5 Today",
        "catalog/movie/zee" to "Popular Movies on Zee5",
        "catalog/series/zee" to "Popular Shows on Zee5"
    )
)
class SonyLivProvider : StremioOttProvider(
    name = "SonyLIV",
    providerId = "sonyliv",
    customMainPage = mainPageOf(
        "catalog/movie/sonyliv" to "Top 10 Movies on SonyLIV Today",
        "catalog/series/sonyliv" to "Top 10 Shows on SonyLIV Today",
        "catalog/movie/sonyliv" to "Popular Movies on SonyLIV",
        "catalog/series/sonyliv" to "Popular Shows on SonyLIV"
    )
)
class AppleTvProvider : StremioOttProvider(
    name = "Apple TV+",
    providerId = "atp",
    customMainPage = mainPageOf(
        "catalog/movie/atp" to "Top 10 Movies on Apple TV+ Today",
        "catalog/series/atp" to "Top 10 Shows on Apple TV+ Today",
        "catalog/movie/atp" to "Popular Movies on Apple TV+",
        "catalog/series/atp" to "Popular Shows on Apple TV+"
    )
)
class CrunchyrollProvider : StremioOttProvider(
    name = "Crunchyroll",
    providerId = "cru",
    defaultType = TvType.Anime,
    customMainPage = mainPageOf(
        "catalog/series/cru" to "Top 10 Anime Series Today",
        "catalog/movie/cru" to "Top 10 Anime Movies Today",
        "catalog/series/cru" to "Popular Anime Series",
        "catalog/movie/cru" to "Popular Anime Movies"
    )
)
