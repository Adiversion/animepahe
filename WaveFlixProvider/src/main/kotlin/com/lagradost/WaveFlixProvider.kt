package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/api/search?q=$encodedQuery"
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
                isMovie = true,
                title = cleanTitle,
                year = year
            )
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, linkData.toJson()) {
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
                } catch (_: Throwable) {
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
                        episode = ep.episodeNumber,
                        title = cleanTitle,
                        year = year
                    )

                    val epPoster = if (!ep.stillPath.isNullOrBlank()) {
                        "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                    } else poster

                    episodes.add(
                        newEpisode(epData.toJson()) {
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

        val nxshaReferer = if (isMovie) {
            "https://nxsha.space/embed/movie/$tmdbId"
        } else {
            "https://nxsha.space/embed/tv/$tmdbId/$season/$episode"
        }

        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val nxshaHeaders = mapOf(
            "User-Agent" to browserUserAgent,
            "Referer" to nxshaReferer
        )

        // 1. Fetch Multi-Language Subtitles from Nxsha Subtitles Engine
        val subtitleJob = async {
            try {
                val subPayload = """{"tmdbId":"$tmdbId","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
                val encSubQuery = NxshaCrypto.encrypt(subPayload)
                val subUrl = "https://nxsha.space/api/subtitles?q=${URLEncoder.encode(encSubQuery, "UTF-8")}"

                val subRes = app.get(subUrl, headers = nxshaHeaders, timeout = 7).text
                val subHash = parseJson<NxshaHashResponse>(subRes).hash
                if (!subHash.isNullOrBlank()) {
                    val decryptedSubJson = NxshaCrypto.decrypt(subHash)
                    if (!decryptedSubJson.isNullOrBlank()) {
                        val subObj = parseJson<NxshaSubtitlesResponse>(decryptedSubJson)
                        subObj.subtitles?.forEach { sub ->
                            val trackUrl = sub.url ?: sub.uri
                            if (!trackUrl.isNullOrBlank()) {
                                val langLabel = sub.title ?: sub.label ?: sub.language ?: sub.srclang ?: "English"
                                subtitleCallback(
                                    newSubtitleFile(
                                        lang = langLabel,
                                        url = trackUrl.trim()
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Graceful fallback if subtitles fail
            }
        }

        // 2. Query Nxsha Multi-Server Playback Engine
        try {
            val serverPayload = """{"tmdbId":"$tmdbId","imdb_id":"","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
            val encServerQuery = NxshaCrypto.encrypt(serverPayload)
            val serverUrl = "https://nxsha.space/api/servers?q=${URLEncoder.encode(encServerQuery, "UTF-8")}"

            val serverRes = app.get(serverUrl, headers = nxshaHeaders, timeout = 10).text
            val serverHash = parseJson<NxshaHashResponse>(serverRes).hash

            if (!serverHash.isNullOrBlank()) {
                val decryptedServerJson = NxshaCrypto.decrypt(serverHash)
                if (!decryptedServerJson.isNullOrBlank()) {
                    val serverList = parseJson<NxshaServerList>(decryptedServerJson)
                    // Query all 33 available servers concurrently (including 4K UHD & direct cloud scrapers)
                    val activeServers = serverList.servers?.filter {
                        !it.scraper.isNullOrBlank() && it.status != "offline"
                    } ?: emptyList()

                    val serverJobs = activeServers.map { srv ->
                        async {
                            try {
                                val sourcePayload = """{"ex_lang":false,"provider":"${srv.scraper}","tmdbId":"$tmdbId","imdb_id":"","type":"${if (isMovie) "movie" else "tv"}","season":$season,"episode":$episode}"""
                                val encSourceQuery = NxshaCrypto.encrypt(sourcePayload)
                                val sourceUrl = "https://nxsha.space/api/sources?q=${URLEncoder.encode(encSourceQuery, "UTF-8")}"

                                val sourceRes = app.get(sourceUrl, headers = nxshaHeaders, timeout = 8).text
                                val sourceHash = parseJson<NxshaHashResponse>(sourceRes).hash ?: return@async
                                val decryptedSourceJson = NxshaCrypto.decrypt(sourceHash) ?: return@async
                                val sourcesObj = parseJson<NxshaSourcesResponse>(decryptedSourceJson)

                                val serverDisplayName = srv.name ?: srv.scraper ?: "Server"

                                sourcesObj.sources?.forEach { src ->
                                    val streamUrl = src.url?.trim()
                                    if (!streamUrl.isNullOrBlank()) {
                                        val qualityLabel = src.label ?: src.quality ?: "Auto"

                                        // Skip 10Gbps Google CDN download links that fail HTTP Range seeking and resume
                                        if (streamUrl.contains("video-downloads.googleusercontent.com") ||
                                            qualityLabel.contains("10Gbps", ignoreCase = true) ||
                                            serverDisplayName.contains("10Gbps", ignoreCase = true)
                                        ) {
                                            return@forEach
                                        }

                                        val qualityInt = parseQuality(qualityLabel)
                                        val isHls = src.type?.lowercase() == "m3u8" || streamUrl.contains(".m3u8")
                                        val streamReferer = src.headers?.get("Referer") ?: "https://nxsha.space/"

                                        val streamHeaders = mapOf(
                                            "User-Agent" to browserUserAgent,
                                            "Referer" to streamReferer,
                                            "Origin" to "https://nxsha.space"
                                        )

                                        if (src.isEmbed == true || src.type?.lowercase() == "embed") {
                                            try {
                                                loadExtractor(streamUrl, streamReferer, subtitleCallback, callback)
                                            } catch (_: Throwable) {}
                                        } else {
                                            // Pre-validate stream to filter out expired 403 / 400 / 404 links, ensure Range seek support, and prevent ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                                            val isPlayable = try {
                                                val probeHeaders = if (isHls) streamHeaders else streamHeaders + ("Range" to "bytes=0-1024")
                                                val check = app.get(streamUrl, headers = probeHeaders, timeout = 5)
                                                val cType = (check.headers["Content-Type"] ?: check.headers["content-type"])?.lowercase() ?: ""
                                                val isNotHtml = !cType.contains("text/html")
                                                (check.isSuccessful || check.code in 200..299) && check.code != 400 && check.code != 403 && isNotHtml
                                            } catch (_: Throwable) {
                                                false
                                            }

                                            if (isPlayable) {
                                                val link = newExtractorLink(
                                                    source = "WaveFlix [$serverDisplayName]",
                                                    name = "$serverDisplayName - $qualityLabel",
                                                    url = streamUrl,
                                                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = streamReferer
                                                    this.headers = streamHeaders
                                                    this.quality = qualityInt
                                                }
                                                synchronized(callback) {
                                                    callback(link)
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                                // Skip individual server failure silently
                            }
                        }
                    }

                    serverJobs.forEach { it.await() }
                }
            }
        } catch (_: Throwable) {
            // Nxsha backbone error handled
        }

        // 3. Direct NetMirror Stream Provider (Multi-Resolution Direct MP4 + Subtitles)
        val netMirrorJob = async {
            try {
                val netMirrorUrl = if (isMovie) {
                    "https://net27.cc/api/embed-tmdb/$tmdbId"
                } else {
                    "https://net27.cc/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode"
                }
                val netHeaders = mapOf(
                    "User-Agent" to browserUserAgent,
                    "Referer" to "https://net27.cc/"
                )
                val netRes = app.get(netMirrorUrl, headers = netHeaders, timeout = 7).text
                val netObj = parseJson<NetMirrorResponse>(netRes)
                if (netObj.ok == true) {
                    netObj.streams?.forEach { s ->
                        val streamUrl = s.url?.trim()
                        if (!streamUrl.isNullOrBlank()) {
                            val resLabel = s.resolution?.toString() ?: "720"
                            val streamHeaders = mapOf(
                                "User-Agent" to browserUserAgent,
                                "Referer" to "https://videodownloader.site/"
                            )
                            val link = newExtractorLink(
                                source = "WaveFlix [NetMirror]",
                                name = "NetMirror - ${resLabel}p [Fast CDN ⚡]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://videodownloader.site/"
                                this.headers = streamHeaders
                                this.quality = parseQuality("${resLabel}p")
                            }
                            synchronized(callback) {
                                callback(link)
                            }
                        }
                    }
                    netObj.captions?.forEach { cap ->
                        val capUrl = cap.url?.trim()
                        if (!capUrl.isNullOrBlank()) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = cap.name ?: cap.lang ?: "English",
                                    url = capUrl
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 4. Direct Vidlink Pro Stream Provider
        val vidlinkJob = async {
            try {
                val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
                val encRes = app.get(encUrl, headers = mapOf("User-Agent" to browserUserAgent), timeout = 6).text
                val encHash = parseJson<VidlinkEncResponse>(encRes).result
                if (!encHash.isNullOrBlank()) {
                    val vidUrl = if (isMovie) {
                        "https://vidlink.pro/api/b/movie/$encHash?multiLang=1"
                    } else {
                        "https://vidlink.pro/api/b/tv/$encHash/$season/$episode?multiLang=1"
                    }
                    val vidHeaders = mapOf(
                        "User-Agent" to browserUserAgent,
                        "Referer" to "https://vidlink.pro/"
                    )
                    val vidRes = app.get(vidUrl, headers = vidHeaders, timeout = 7).text
                    val vidObj = parseJson<VidlinkResponse>(vidRes)
                    vidObj.stream?.qualities?.forEach { (qKey, entry) ->
                        val streamUrl = entry.url?.trim()
                        if (!streamUrl.isNullOrBlank()) {
                            val streamHeaders = mapOf(
                                "User-Agent" to browserUserAgent,
                                "Referer" to "https://vidlink.pro/"
                            )
                            val link = newExtractorLink(
                                source = "WaveFlix [Vidlink]",
                                name = "Vidlink - ${qKey}p [Direct Stream ⚡]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://vidlink.pro/"
                                this.headers = streamHeaders
                                this.quality = parseQuality("${qKey}p")
                            }
                            synchronized(callback) {
                                callback(link)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // 5. Direct RogMovies Indian Cloud & Multi-Audio Integration
        val rogMoviesJob = async {
            try {
                val titleToSearch = linkData.title ?: runCatching {
                    val tmdbMetaUrl = "https://api.themoviedb.org/3/${if (isMovie) "movie" else "tv"}/$tmdbId?api_key=e6c2364656962cbcb82dd76660285f39"
                    val json = app.get(tmdbMetaUrl, timeout = 5).parsedSafe<Map<String, Any?>>()
                    (json?.get("title") ?: json?.get("name")) as? String
                }.getOrNull()

                if (!titleToSearch.isNullOrBlank()) {
                    queryRogMovies(titleToSearch, linkData.year, isMovie, season, episode, subtitleCallback, callback)
                }
            } catch (_: Throwable) {}
        }

        // 6. Direct BollyZone Daily Indian TV Serial Integration
        val bollyZoneJob = async {
            if (!isMovie) {
                try {
                    val titleToSearch = linkData.title ?: runCatching {
                        val tmdbMetaUrl = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=e6c2364656962cbcb82dd76660285f39"
                        val json = app.get(tmdbMetaUrl, timeout = 5).parsedSafe<Map<String, Any?>>()
                        (json?.get("title") ?: json?.get("name")) as? String
                    }.getOrNull()

                    if (!titleToSearch.isNullOrBlank()) {
                        queryBollyZone(titleToSearch, season, episode, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            }
        }

        // 7. High-Reliability Redundant Fallback Extractors
        val fallbackUrls = mutableListOf<String>()
        if (isMovie) {
            fallbackUrls.add("https://2embed.cc/embed/$tmdbId")
            fallbackUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1")
            fallbackUrls.add("https://vidsrc.net/embed/movie/$tmdbId")
            fallbackUrls.add("https://player.smashystream.com/movie/$tmdbId")
        } else {
            fallbackUrls.add("https://2embed.cc/embedtv/$tmdbId&s=$season&e=$episode")
            fallbackUrls.add("https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode")
            fallbackUrls.add("https://vidsrc.net/embed/tv/$tmdbId/$season/$episode")
            fallbackUrls.add("https://player.smashystream.com/tv/$tmdbId/$season/$episode")
        }

        val fallbackJobs = fallbackUrls.map { url ->
            async {
                try {
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        netMirrorJob.await()
        vidlinkJob.await()
        rogMoviesJob.await()
        bollyZoneJob.await()
        fallbackJobs.forEach { it.await() }
        subtitleJob.await()
        true
    }

    private suspend fun queryBollyZone(
        query: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanQuery = query.replace("Dhamaal with ", "", ignoreCase = true)
            .replace("Dhamaal", "", ignoreCase = true)
            .trim()
            .ifBlank { query }

        val searchUrl = "https://www.bollyzone.to/?s=${URLEncoder.encode(cleanQuery, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val searchHtml = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7).text
        }.getOrNull() ?: return

        val doc = org.jsoup.Jsoup.parse(searchHtml)
        val posts = doc.select("a[href*='/series/']").map { a ->
            a.attr("href").trim() to a.text().trim()
        }.filter { it.second.length > 5 }

        // Find episode posts matching query
        val targetPosts = posts.filter { (_, text) ->
            text.contains("panga", ignoreCase = true) || text.contains(cleanQuery, ignoreCase = true)
        }.distinctBy { it.first }

        targetPosts.take(2).forEach { (postUrl, _) ->
            val postHtml = runCatching {
                app.get(postUrl, headers = headers, timeout = 8).text
            }.getOrNull() ?: return@forEach

            val pDoc = org.jsoup.Jsoup.parse(postHtml)
            pDoc.select("a[href*='groundbanks.net/item.php']").forEach { a ->
                val gbUrl = a.attr("href").trim()
                if (gbUrl.startsWith("http")) {
                    runCatching {
                        loadExtractor(gbUrl, postUrl, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private suspend fun queryRogMovies(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "https://rogmovies.one/ts-search.php?q=${URLEncoder.encode(query, "UTF-8")}&page=1"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://rogmovies.one/?s="
        )
        val jsonRes = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7).text
        }.getOrNull() ?: return

        val data = runCatching { parseJson<RogSearchResponse>(jsonRes) }.getOrNull() ?: return
        val hits = data.hits ?: return

        val cleanQuery = query.lowercase().trim()
        val relevantHits = hits.filter { hit ->
            val hitTitle = hit.document?.post_title?.lowercase() ?: ""
            hitTitle.contains(cleanQuery)
        }

        relevantHits.take(2).forEach { hit ->
            val permalink = hit.document?.permalink ?: return@forEach
            val postUrl = if (permalink.startsWith("http")) permalink else "https://rogmovies.one$permalink"
            val postHtml = runCatching {
                app.get(postUrl, headers = headers, timeout = 8).text
            }.getOrNull() ?: return@forEach

            val doc = org.jsoup.Jsoup.parse(postHtml)
            doc.select("a[href*='nexdrive.'], a[href*='vcloud.'], a[href*='hubcloud.'], a[href*='fastdl.'], a[href*='fast-dl.'], a[href*='zee-dl.']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http")) {
                    runCatching {
                        loadExtractor(href, postUrl, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private fun parseQuality(qualityStr: String?): Int {
        if (qualityStr.isNullOrBlank()) return Qualities.Unknown.value
        val match = Regex("""(\d{3,4})p?""").find(qualityStr)
        return when (match?.groupValues?.get(1)?.toIntOrNull()) {
            2160, 4 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            else -> when {
                qualityStr.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                qualityStr.contains("1080", ignoreCase = true) || qualityStr.contains("fhd", ignoreCase = true) -> Qualities.P1080.value
                qualityStr.contains("720", ignoreCase = true) || qualityStr.contains("hd", ignoreCase = true) -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
        }
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
            }
        } else {
            newTvSeriesSearchResponse(titleVal, targetUrl, TvType.TvSeries) {
                this.posterUrl = poster
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
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogSearchResponse(
        @JsonProperty("found") val found: Int? = null,
        @JsonProperty("hits") val hits: List<RogHitItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogHitItem(
        @JsonProperty("document") val document: RogDocument? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RogDocument(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("post_title") val post_title: String? = null,
        @JsonProperty("permalink") val permalink: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaHashResponse(
        @JsonProperty("_hash") val hash: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaServerList(
        @JsonProperty("servers") val servers: List<NxshaServerItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaServerItem(
        @JsonProperty("id") val id: Any? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("scraper") val scraper: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("web_support") val webSupport: Boolean? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSourcesResponse(
        @JsonProperty("sources") val sources: List<NxshaSourceItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSourceItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("org_uri") val orgUri: String? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("isEmbed") val isEmbed: Boolean? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSubtitlesResponse(
        @JsonProperty("subtitles") val subtitles: List<NxshaSubtitleItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NxshaSubtitleItem(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("srclang") val srclang: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("uri") val uri: String? = null
    )

    // NetMirror DTOs
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("streams") val streams: List<NetMirrorStreamItem>? = null,
        @JsonProperty("captions") val captions: List<NetMirrorCaptionItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorStreamItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Any? = null,
        @JsonProperty("size") val size: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NetMirrorCaptionItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    // Vidlink DTOs
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkEncResponse(
        @JsonProperty("result") val result: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkResponse(
        @JsonProperty("stream") val stream: VidlinkStreamObj? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkStreamObj(
        @JsonProperty("qualities") val qualities: Map<String, VidlinkQualityEntry>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VidlinkQualityEntry(
        @JsonProperty("url") val url: String? = null
    )

    // Nxsha AES-256-CBC Crypto Implementation (OpenSSL EVP_BytesToKey)
    object NxshaCrypto {
        private const val KEY = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

        private fun b64Encode(bytes: ByteArray): String {
            return try {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getEncoder().encodeToString(bytes)
            }
        }

        private fun b64Decode(str: String): ByteArray {
            return try {
                Base64.decode(str, Base64.DEFAULT)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(str)
            }
        }

        private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int = 32, ivLen: Int = 16): Pair<ByteArray, ByteArray> {
            val md5 = MessageDigest.getInstance("MD5")
            val totalLen = keyLen + ivLen
            val combined = ByteArray(totalLen)
            var currentLen = 0
            var d = ByteArray(0)

            while (currentLen < totalLen) {
                md5.reset()
                if (d.isNotEmpty()) {
                    md5.update(d)
                }
                md5.update(password)
                md5.update(salt)
                d = md5.digest()

                val copyLen = minOf(d.size, totalLen - currentLen)
                System.arraycopy(d, 0, combined, currentLen, copyLen)
                currentLen += copyLen
            }

            val key = combined.copyOfRange(0, keyLen)
            val iv = combined.copyOfRange(keyLen, totalLen)
            return Pair(key, iv)
        }

        fun encrypt(jsonString: String): String {
            val ts = System.currentTimeMillis()
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val saltRandom = (1..10).map { chars.random() }.joinToString("")
            val payload = if (jsonString.endsWith("}")) {
                jsonString.dropLast(1) + ""","_req_ts":$ts,"_req_salt":"$saltRandom"}"""
            } else {
                """{"_req_ts":$ts,"_req_salt":"$saltRandom"}"""
            }

            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

            val header = "Salted__".toByteArray(Charsets.UTF_8)
            val total = ByteArray(header.size + salt.size + encrypted.size)
            System.arraycopy(header, 0, total, 0, header.size)
            System.arraycopy(salt, 0, total, header.size, salt.size)
            System.arraycopy(encrypted, 0, total, header.size + salt.size, encrypted.size)

            val base64 = b64Encode(total)
            return base64.replace('+', '-').replace('/', '_').trimEnd('=')
        }

        fun decrypt(encryptedBase64Url: String): String? {
            return try {
                var b64 = encryptedBase64Url.replace('-', '+').replace('_', '/')
                val remainder = b64.length % 4
                if (remainder != 0) {
                    b64 += "=".repeat(4 - remainder)
                }
                val raw = b64Decode(b64)
                if (raw.size < 16) return null
                val header = String(raw.copyOfRange(0, 8), Charsets.UTF_8)
                if (header != "Salted__") return null
                val salt = raw.copyOfRange(8, 16)
                val ciphertext = raw.copyOfRange(16, raw.size)

                val (key, iv) = evpBytesToKey(KEY.toByteArray(Charsets.UTF_8), salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val decryptedBytes = cipher.doFinal(ciphertext)
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
