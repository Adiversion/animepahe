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
    val defaultType: TvType = TvType.TvSeries
) : MainAPI() {
    override var mainUrl = "https://www.waveflix.online"
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val hasMainPage = true

    override val mainPage = if (defaultType == TvType.Anime) {
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
        val raw = "dnp,amp,atp,hbm,nfx,zee,jhs,sonyliv,cru::$country:1792722613240:0:0:"
        val b64 = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return URLEncoder.encode(b64, "UTF-8")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = getUserCountry()
        val cacheKey = "$name/$country/${request.data}/$page"
        val now = System.currentTimeMillis()

        synchronized(ottCache) {
            val cached = ottCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val token = getCatalogToken(country)
        val catalogUrl = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/$token/${request.data}.json"

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

        val response = newHomePageResponse(request.name, items, hasNext = false)
        synchronized(ottCache) {
            ottCache[cacheKey] = now to response
        }
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return sharedEngine.search(query)
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

class NetflixProvider : StremioOttProvider("Netflix", "nfx")
class JioHotstarProvider : StremioOttProvider("JioHotstar", "jhs")
class PrimeVideoProvider : StremioOttProvider("Amazon Prime", "amp")
class Zee5Provider : StremioOttProvider("Zee5", "zee")
class SonyLivProvider : StremioOttProvider("SonyLIV", "sonyliv")
class AppleTvProvider : StremioOttProvider("Apple TV+", "atp")
class CrunchyrollProvider : StremioOttProvider("Crunchyroll", "cru", defaultType = TvType.Anime)
