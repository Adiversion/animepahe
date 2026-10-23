package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
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

    companion object {
        private val homeCache = mutableMapOf<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes cache for smooth UI
    }

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
        val cacheKey = "${request.data}/$page"
        val now = System.currentTimeMillis()
        synchronized(homeCache) {
            val cached = homeCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return cached.second
            }
        }

        val parts = request.data.split("/")
        val mediaType = parts.getOrNull(0) ?: "movie"
        val category = parts.getOrNull(1) ?: "trending"

        val endpoint = if (mediaType == "anime") {
            "$mainUrl/api/discover?mediaType=anime&page=$page"
        } else {
            "$mainUrl/api/discover?mediaType=$mediaType&category=$category&page=$page"
        }

        val res = app.get(endpoint, headers = commonHeaders, timeout = 8)
            .parsedSafe<WaveSearchResponse>() ?: throw ErrorLoadingException("Failed to fetch WaveFlix discover catalog")

        val homeItems = res.results?.mapNotNull { item ->
            item.toSearchResponse()
        } ?: emptyList()

        val hasNext = page < (res.totalPages ?: 1)
        val response = newHomePageResponse(request.name, homeItems, hasNext)
        synchronized(homeCache) {
            homeCache[cacheKey] = now to response
        }
        return response
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

            fun addSeasonEpisodes(seasonNum: Int, epList: List<WaveEpisodeItem>) {
                epList.forEachIndexed { idx, ep ->
                    val epNum = ep.episodeNumber ?: (idx + 1)
                    val epData = WaveLinkData(
                        tmdbId = tmdbId,
                        isMovie = false,
                        season = seasonNum,
                        episode = epNum,
                        title = cleanTitle,
                        year = year
                    )

                    val epPoster = if (!ep.stillPath.isNullOrBlank()) {
                        "https://image.tmdb.org/t/p/w300${ep.stillPath}"
                    } else poster

                    episodes.add(
                        newEpisode(epData.toJson()) {
                            this.name = ep.name ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.description = ep.overview
                        }
                    )
                }
            }

            // 1. Fetch Season 1 first
            val s1Res = try {
                app.get("$mainUrl/api/season?tvId=$tmdbId&season=1", headers = commonHeaders, timeout = 7)
                    .parsedSafe<WaveSeasonResponse>()
            } catch (_: Throwable) {
                null
            }

            val s1List = s1Res?.episodes
            if (!s1List.isNullOrEmpty()) {
                addSeasonEpisodes(1, s1List)

                // 2. Fetch subsequent seasons in concurrent batches of 4
                coroutineScope {
                    var nextBatchStart = 2
                    var hasMore = true
                    while (hasMore && nextBatchStart <= 25) {
                        val batchRange = nextBatchStart until (nextBatchStart + 4)
                        val seasonJobs = batchRange.map { sNum ->
                            async {
                                val sRes = try {
                                    app.get("$mainUrl/api/season?tvId=$tmdbId&season=$sNum", headers = commonHeaders, timeout = 7)
                                        .parsedSafe<WaveSeasonResponse>()
                                } catch (_: Throwable) {
                                    null
                                }
                                sNum to (sRes?.episodes ?: emptyList())
                            }
                        }
                        val results = seasonJobs.awaitAll().sortedBy { it.first }
                        for ((sNum, eps) in results) {
                            if (eps.isNotEmpty()) {
                                addSeasonEpisodes(sNum, eps)
                            } else {
                                hasMore = false
                                break
                            }
                        }
                        nextBatchStart += 4
                    }
                }
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
        val nxshaJob = async {
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

                                    val streamJobs = sourcesObj.sources?.mapNotNull { src ->
                                        val streamUrl = src.url?.trim()
                                        if (streamUrl.isNullOrBlank()) return@mapNotNull null
                                        val qualityLabel = src.label ?: src.quality ?: "Auto"

                                        // Skip 10Gbps Google CDN download links that fail HTTP Range seeking and resume
                                        if (streamUrl.contains("video-downloads.googleusercontent.com") ||
                                            qualityLabel.contains("10Gbps", ignoreCase = true) ||
                                            serverDisplayName.contains("10Gbps", ignoreCase = true)
                                        ) {
                                            return@mapNotNull null
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
                                            // Avoid slow blocking WebViews for known Cloudflare/captcha-walled hosts
                                            if (!streamUrl.contains("filepress") && !streamUrl.contains("filebee")) {
                                                async {
                                                    try {
                                                        loadExtractor(streamUrl, streamReferer, subtitleCallback, callback)
                                                    } catch (_: Throwable) {}
                                                }
                                            } else null
                                        } else {
                                            async {
                                                // Fast-probe stream with 2s timeout to filter out dead links without lagging UI
                                                val isPlayable = try {
                                                    val probeHeaders = if (isHls) streamHeaders else streamHeaders + ("Range" to "bytes=0-1024")
                                                    val check = app.get(streamUrl, headers = probeHeaders, timeout = 2)
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
                                    streamJobs?.awaitAll()
                                } catch (_: Throwable) {
                                    // Skip individual server failure silently
                                }
                            }
                        }

                        serverJobs.awaitAll()
                    }
                }
            } catch (_: Throwable) {
                // Nxsha backbone error handled
            }
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

        val resolvedTitle = linkData.title

        // 5. Direct RogMovies Indian Cloud & Multi-Audio Integration
        val rogMoviesJob = async {
            if (!resolvedTitle.isNullOrBlank()) {
                try {
                    queryRogMovies(resolvedTitle, linkData.year, isMovie, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        // 6. Direct BollyZone Daily Indian TV Serial Integration
        val bollyZoneJob = async {
            if (!isMovie && !resolvedTitle.isNullOrBlank()) {
                try {
                    queryBollyZone(resolvedTitle, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        // 7. Direct MultiMovies Integration (Cineverse / Filmu / Nexus / VidBolt / Vidrift)
        val multiMoviesJob = async {
            try {
                queryMultiMovies(tmdbId.toString(), null, isMovie, season, episode, subtitleCallback, callback)
            } catch (_: Throwable) {}
        }

        // 8. Direct Desi Serials Daily Indian TV Serial Integration
        val desiSerialsJob = async {
            if (!isMovie && !resolvedTitle.isNullOrBlank()) {
                try {
                    queryDesiSerials(resolvedTitle, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        // 9. Direct Bollyflix Indian Cloud Integration
        val bollyflixJob = async {
            if (!resolvedTitle.isNullOrBlank()) {
                try {
                    queryBollyflix(resolvedTitle, linkData.year, isMovie, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        // 10. KatMovieHD – Direct Indian cloud search via new.katmoviehd.top + links.kmhd.me
        val katMovieJob = async {
            if (!resolvedTitle.isNullOrBlank()) {
                try {
                    queryKatMovieHD(resolvedTitle, linkData.year, isMovie, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        // 11. HDHub4u – Direct Indian cloud search via new8.hdhub4u.cl + greenmotors
        val hdhub4uJob = async {
            if (!resolvedTitle.isNullOrBlank()) {
                try {
                    queryHDHub4u(resolvedTitle, linkData.year, isMovie, season, episode, subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }

        nxshaJob.await()
        netMirrorJob.await()
        vidlinkJob.await()
        rogMoviesJob.await()
        bollyZoneJob.await()
        multiMoviesJob.await()
        desiSerialsJob.await()
        bollyflixJob.await()
        katMovieJob.await()
        hdhub4uJob.await()
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
        val cleanQuery = query.substringBefore("–").substringBefore("-")
            .replace("Dhamaal with ", "", ignoreCase = true)
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
        val articles = doc.select("article")
        val posts = mutableListOf<Pair<String, String>>()
        for (art in articles) {
            val href = art.selectFirst("a[href*='/series/']")?.attr("href")?.trim() ?: continue
            val title = art.selectFirst(".Title, h2, h3, a")?.text()?.trim() ?: ""
            if (href.isNotBlank()) posts.add(href to title)
        }

        // Find episode posts matching query & season
        val targetPosts = posts.filter { (_, text) ->
            val lower = text.lowercase()
            lower.contains("panga") && if (season == 2) (lower.contains(" 2 ") || lower.contains("2026")) else !lower.contains(" 2 ")
        }.distinctBy { it.first }

        val chronoPosts = targetPosts.reversed()
        val targetPost = if (episode in 1..chronoPosts.size) chronoPosts[episode - 1] else targetPosts.firstOrNull() ?: return

        val postHtml = runCatching {
            app.get(targetPost.first, headers = headers, timeout = 8).text
        }.getOrNull() ?: return

        val pDoc = org.jsoup.Jsoup.parse(postHtml)
        for (a in pDoc.select("a[href*='groundbanks.net/item.php']")) {
            val gbUrl = a.attr("href").trim()
            if (gbUrl.startsWith("http")) {
                runCatching {
                    loadExtractor(gbUrl, targetPost.first, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun queryMultiMovies(
        tmdbId: String,
        imdbId: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val servers = mutableListOf<String>()
        if (isMovie) {
            servers.add("https://embed.filmu.in/movie/$tmdbId")
            servers.add("https://rozgarlelo.modiplay.xyz/embed/tmdb/movie?id=$tmdbId")
            servers.add("https://vidbolt.xyz/movie/$tmdbId")
            servers.add("https://embed.vidrift.net/embed/movie/$tmdbId")
            servers.add("https://www.moviesnexus.fun/movie/$tmdbId?sv=multi-audio")
            if (!imdbId.isNullOrBlank()) {
                servers.add("https://streams.iqsmartgames.com/embed/movie/$imdbId?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4")
                servers.add("https://vidout.pages.dev/movie/$imdbId")
            }
        } else {
            servers.add("https://embed.filmu.in/tv/$tmdbId/$season/$episode")
            servers.add("https://rozgarlelo.modiplay.xyz/embed/tmdb/tv?id=$tmdbId&s=$season&e=$episode")
            servers.add("https://vidbolt.xyz/tv/$tmdbId/$season/$episode")
            servers.add("https://embed.vidrift.net/embed/tv/$tmdbId/$season/$episode")
            servers.add("https://www.moviesnexus.fun/tv/$tmdbId/$season/$episode")
            servers.add("https://streams.iqsmartgames.com/embed/tv/$tmdbId/$season/$episode?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4")
            servers.add("https://vidout.pages.dev/tv/$tmdbId/s$season/e$episode")
        }

        val jobs = servers.map { sUrl ->
            async {
                try {
                    loadExtractor(sUrl, "https://m1.multimovies.courses/", subtitleCallback, callback)
                } catch (_: Throwable) {}
            }
        }
        jobs.forEach { it.await() }
    }

    private suspend fun queryDesiSerials(
        query: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanQuery = query.substringBefore("–").substringBefore("-").lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        val stopWords = setOf("aur", "the", "and", "with", "all", "season", "episode")
        val significantTokens = cleanQuery.split(" ").filter { it.length > 2 && it !in stopWords }
        if (significantTokens.isEmpty()) return

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val homeHtml = runCatching {
            app.get("https://www.desi-serials.to/", headers = headers, timeout = 7).text
        }.getOrNull() ?: return

        val doc = org.jsoup.Jsoup.parse(homeHtml)
        val matchingShow = doc.select("a[href*='/watch-online/']").maxByOrNull { a ->
            val text = a.text().lowercase()
            significantTokens.count { text.contains(it) }
        }?.takeIf { a ->
            val text = a.text().lowercase()
            significantTokens.any { text.contains(it) }
        } ?: return

        val showUrl = matchingShow.attr("href")
        if (showUrl.isBlank()) return

        val showHtml = runCatching {
            app.get(showUrl, headers = headers + ("Referer" to "https://www.desi-serials.to/"), timeout = 7).text
        }.getOrNull() ?: return

        val showDoc = org.jsoup.Jsoup.parse(showHtml)
        val episodeList = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (a in showDoc.select("a[href*='-episode-']")) {
            val href = a.attr("href").trim()
            val text = a.text().lowercase()
            if (href.isNotBlank() && !seen.contains(href) && !text.contains("promo")) {
                seen.add(href)
                episodeList.add(href)
            }
        }
        val chronoList = episodeList.reversed()
        val episodeLink = if (episode in 1..chronoList.size) chronoList[episode - 1] else episodeList.firstOrNull() ?: return

        val epHtml = runCatching {
            app.get(episodeLink, headers = headers + ("Referer" to showUrl), timeout = 7).text
        }.getOrNull() ?: return

        val epDoc = org.jsoup.Jsoup.parse(epHtml)
        for (a in epDoc.select("a[href*='tvarticles.org']")) {
            val link = a.attr("href").trim()
            if (link.isNotBlank()) {
                runCatching {
                    loadExtractor(link, episodeLink, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun queryKatMovieHD(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val katHeaders = mapOf(
            "User-Agent" to browserUA,
            "Referer" to "https://new.katmoviehd.top/"
        )

        val cleanTitleKebab = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val candidateSlugs = mutableListOf<String>()

        // 1. Direct High-Probability Slug Guesses
        if (isMovie) {
            if (year != null) {
                candidateSlugs.add("$cleanTitleKebab-$year-hindi")
                candidateSlugs.add("$cleanTitleKebab-$year")
            }
            candidateSlugs.add("$cleanTitleKebab-hindi")
            candidateSlugs.add(cleanTitleKebab)
        } else {
            candidateSlugs.add("$cleanTitleKebab-s$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-s0$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-season-$season-hindi")
            candidateSlugs.add("$cleanTitleKebab-hindi")
        }

        // 2. Scan KatMovieHD recent posts & catalog SSR pages for matching title tokens
        val tokens = cleanTitleKebab.split("-").filter { it.length > 2 }
        if (tokens.isNotEmpty()) {
            val pagesToScan = listOf("https://new.katmoviehd.top/", "https://new.katmoviehd.top/?page=2")
            pagesToScan.forEach { pageUrl ->
                val pageHtml = runCatching {
                    app.get(pageUrl, headers = katHeaders, timeout = 7).text
                }.getOrNull()
                if (!pageHtml.isNullOrBlank()) {
                    Regex("""slug:"([a-z0-9\-]+)"""").findAll(pageHtml).forEach { m ->
                        val slug = m.groupValues[1]
                        if (tokens.any { slug.contains(it) }) {
                            candidateSlugs.add(slug)
                        }
                    }
                }
            }
        }

        val uniqueSlugs = candidateSlugs.distinct().take(4)
        if (uniqueSlugs.isEmpty()) return@coroutineScope

        // 3. For each candidate slug, fetch post HTML & resolve kmhd.me play/file/pack links
        val slugJobs = uniqueSlugs.map { slug ->
            async {
                try {
                    val postUrl = "https://new.katmoviehd.top/$slug/"
                    val postHtml = runCatching {
                        app.get(postUrl, headers = katHeaders, timeout = 8).text
                    }.getOrNull() ?: return@async

                    // Extract all kmhd.me play links, file links, and pack links
                    val playIds = Regex("""links\.kmhd\.me/play\?id=([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()
                    val fileIds = Regex("""links\.kmhd\.me/file/([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()
                    val packIds = Regex("""links\.kmhd\.me/pack/([a-zA-Z0-9_\-]+)""").findAll(postHtml)
                        .map { it.groupValues[1] }.distinct().toList()

                    // Resolve TV pack pages first if it's a TV show
                    if (!isMovie && packIds.isNotEmpty()) {
                        packIds.take(3).forEach { packId ->
                            try {
                                resolveKmhdPack(packId, season, episode, katHeaders, callback)
                            } catch (_: Throwable) {}
                        }
                    }

                    // Resolve play?id pages
                    val playJobs = playIds.take(4).map { packId ->
                        async {
                            try {
                                resolveKmhdPlay(packId, isMovie, season, episode, katHeaders, callback)
                            } catch (_: Throwable) {}
                        }
                    }

                    // Resolve individual file links directly
                    val fileJobs = fileIds.take(6).map { fileId ->
                        async {
                            try {
                                resolveKmhdFile(fileId, katHeaders, callback)
                            } catch (_: Throwable) {}
                        }
                    }

                    (playJobs + fileJobs).forEach { it.await() }
                } catch (_: Throwable) {}
            }
        }
        slugJobs.forEach { it.await() }
    }

    /**
     * Resolves a links.kmhd.me/pack/{packId} TV pack page.
     * Maps episodes to file IDs and queries touchme API for matching episode.
     */
    private suspend fun resolveKmhdPack(
        packId: String,
        season: Int,
        episode: Int,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val packUrl = "https://links.kmhd.me/pack/$packId"
        val packHtml = runCatching {
            app.get(packUrl, headers = katHeaders, timeout = 8).text
        }.getOrNull() ?: return

        // Pattern matching for target episode e.g. S03E01, 3x01, or Episode 01
        val epPattern = Regex("""(?:S0?${season}E0?${episode}|${season}x0?${episode}|E0?${episode}\b|Episode\s*0?${episode}\b)""", RegexOption.IGNORE_CASE)

        // Parse info object: info:{ FileId: { name: "..." }, ... }
        val infoMatches = Regex("""([a-zA-Z0-9_\-]+)\s*:\s*\{\s*name\s*:\s*"([^"]+)"""").findAll(packHtml)
        var matchedFileId: String? = null
        for (m in infoMatches) {
            val fileId = m.groupValues[1]
            val fileName = m.groupValues[2]
            if (epPattern.containsMatchIn(fileName)) {
                matchedFileId = fileId
                break
            }
        }

        if (matchedFileId != null) {
            resolveKmhdFile(matchedFileId, katHeaders, callback)
        }
    }

    /**
     * Resolves a links.kmhd.me/play?id={playId} page.
     */
    private suspend fun resolveKmhdPlay(
        playId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val playUrl = "https://links.kmhd.me/play?id=$playId"
        val playHtml = runCatching {
            app.get(playUrl, headers = katHeaders, timeout = 8).text
        }.getOrNull() ?: return

        // Direct Streamtape embed resolution from inline script
        Regex("""streamtape_res\s*:\s*"([a-zA-Z0-9_\-]+)"""").findAll(playHtml).forEach { m ->
            val tapeId = m.groupValues[1]
            if (tapeId != "None" && tapeId.isNotBlank()) {
                runCatching {
                    loadExtractor("https://streamtape.com/e/$tapeId", playUrl, { _ -> }, callback)
                }
            }
        }

        // Direct Streamwish embed resolution from inline script
        Regex("""streamwish_res\s*:\s*"([a-zA-Z0-9_\-]+)"""").findAll(playHtml).forEach { m ->
            val wishId = m.groupValues[1]
            if (wishId != "None" && wishId.isNotBlank()) {
                runCatching {
                    loadExtractor("https://hglink.to/e/$wishId", playUrl, { _ -> }, callback)
                }
            }
        }

        // Extract FileIds embedded in info block
        val fileIds = Regex("""([a-zA-Z0-9_\-]+)\s*:\s*\{[^}]*name""").findAll(playHtml)
            .map { it.groupValues[1] }
            .filter { it != "info" && it != "data" && it != "page_ads" }
            .distinct()
            .toList()

        fileIds.take(4).forEach { fileId ->
            resolveKmhdFile(fileId, katHeaders, callback)
        }
    }

    /**
     * Resolves a links.kmhd.me fileId via the touchme API.
     * Hubdrive (Hubcloud R2/PixelServer), Streamtape, Streamwish, and Katdrive.
     */
    private suspend fun resolveKmhdFile(
        fileId: String,
        katHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolvers = listOf(
            "hubdrive_res"   to "https://hubcloud.ist/drive/",
            "streamtape_res" to "https://streamtape.com/e/",
            "streamwish_res" to "https://hglink.to/e/",
            "katdrive_res"   to "https://katdrive.click/file/"
        )

        resolvers.forEach { (resolver, baseUrl) ->
            try {
                val touchmeUrl = "https://links.kmhd.me/api/touchme/$fileId?c=$resolver"
                val resp = app.post(
                    touchmeUrl,
                    headers = katHeaders + mapOf("Content-Type" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                    timeout = 7
                ).text

                if (Regex(""""status"\s*:\s*"Done"""").containsMatchIn(resp)) {
                    val linkId = Regex(""""linkId"\s*:\s*"([^"]+)"""").find(resp)?.groupValues?.get(1)
                    if (!linkId.isNullOrBlank() && linkId != "None") {
                        val fullUrl = if (linkId.startsWith("http")) linkId else baseUrl + linkId
                        runCatching {
                            loadExtractor(fullUrl, "https://links.kmhd.me/", { _ -> }, callback)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private suspend fun queryHDHub4u(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val hdhubHeaders = mapOf(
            "User-Agent" to browserUA,
            "Referer" to "https://new8.hdhub4u.cl/"
        )

        val cleanQuery = query.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
        val searchUrl = "https://new8.hdhub4u.cl/?s=${cleanQuery.replace(" ", "+")}"

        val searchHtml = runCatching {
            app.get(searchUrl, headers = hdhubHeaders, timeout = 8).text
        }.getOrNull() ?: return@coroutineScope

        val doc = Jsoup.parse(searchHtml)
        val posts = mutableListOf<Pair<String, String>>() // (url, title)

        doc.select("li.thumb").forEach { thumb ->
            val a = thumb.selectFirst("figure a, figcaption a")
            val href = a?.attr("href")?.trim() ?: return@forEach
            val title = a.attr("aria-label").ifBlank { thumb.selectFirst("figcaption p")?.text() ?: "" }.trim()
            if (href.isNotBlank() && title.isNotBlank()) {
                val fullUrl = if (href.startsWith("http")) href else "https://new8.hdhub4u.cl" + (if (href.startsWith("/")) href else "/$href")
                posts.add(fullUrl to title)
            }
        }

        if (posts.isEmpty()) return@coroutineScope

        val queryTokens = cleanQuery.lowercase().split(" ").filter { it.length > 2 }

        val matchingPosts = posts.filter { (_, title) ->
            val lower = title.lowercase()
            val matchesTokens = if (queryTokens.isEmpty()) true else {
                queryTokens.all { lower.contains(it) } || (queryTokens.size >= 2 && queryTokens.count { lower.contains(it) } >= queryTokens.size - 1)
            }
            if (!matchesTokens) return@filter false

            if (isMovie) {
                val isTvTitle = lower.contains("season") || lower.contains("all episode") || Regex("""\bs\d{1,2}\b""").containsMatchIn(lower)
                if (isTvTitle) return@filter false
                if (year != null) {
                    val yearMatches = lower.contains(year.toString()) || lower.contains((year - 1).toString()) || lower.contains((year + 1).toString())
                    yearMatches || queryTokens.size >= 2
                } else true
            } else {
                val seasonPatterns = listOf(
                    "season $season",
                    "season 0$season",
                    "s0$season",
                    "s$season",
                    "s $season"
                )
                seasonPatterns.any { lower.contains(it) }
            }
        }.take(3)

        if (matchingPosts.isEmpty()) return@coroutineScope

        val postJobs = matchingPosts.map { (postUrl, _) ->
            async {
                try {
                    val postHtml = runCatching {
                        app.get(postUrl, headers = hdhubHeaders, timeout = 8).text
                    }.getOrNull() ?: return@async

                    if (isMovie) {
                        val docPost = Jsoup.parse(postHtml)
                        for (a in docPost.select("a[href]")) {
                            val href = a.attr("href").trim()
                            if (href.contains("greenmotors.") || href.contains("hubdrive.") ||
                                href.contains("hubcloud.") || href.contains("hblinks.") ||
                                href.contains("hubcdn.")) {
                                runCatching {
                                    loadExtractor(href, postUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    } else {
                        val padEp = episode.toString().padStart(2, '0')
                        val rawEp = episode.toString()
                        val epRegex = Regex("""(?:E|Episode|EP)[\s.-]*(?:0?$rawEp|$padEp)\b[^<]*?(?:<a[\s\S]*?<\/h[1-6]>|<a[\s\S]*?<\/p>|<a[\s\S]*?<\/div>|$)""", RegexOption.IGNORE_CASE)
                        val epMatches = epRegex.findAll(postHtml).toList()

                        if (epMatches.isNotEmpty()) {
                            val aRegex = Regex("""href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                            for (m in epMatches) {
                                for (aMatch in aRegex.findAll(m.value)) {
                                    val linkUrl = aMatch.groupValues[1]
                                    if (linkUrl.contains("greenmotors.") || linkUrl.contains("hubdrive.") ||
                                        linkUrl.contains("hubcloud.") || linkUrl.contains("hblinks.") ||
                                        linkUrl.contains("hubcdn.")) {
                                        runCatching {
                                            loadExtractor(linkUrl, postUrl, subtitleCallback, callback)
                                        }
                                    }
                                }
                            }
                        } else {
                            val epHrefRegex = Regex("""<a[^>]*href=["'](https?://[^"']+)["'][^>]*>[\s\S]*?(?:E|Episode|EP)[\s.-]*(?:0?$rawEp|$padEp)\b[\s\S]*?<\/a>""", RegexOption.IGNORE_CASE)
                            for (m in epHrefRegex.findAll(postHtml)) {
                                val linkUrl = m.groupValues[1]
                                if (linkUrl.contains("greenmotors.") || linkUrl.contains("hubdrive.") ||
                                    linkUrl.contains("hubcloud.") || linkUrl.contains("hblinks.") ||
                                    linkUrl.contains("hubcdn.")) {
                                    runCatching {
                                        loadExtractor(linkUrl, postUrl, subtitleCallback, callback)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        postJobs.awaitAll()
    }

    private suspend fun queryBollyflix(
        query: String,
        year: Int?,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        val searchUrl = "https://bollyflix.co.za/?s=${URLEncoder.encode(cleanQuery, "UTF-8")}"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://bollyflix.co.za/"
        )
        val searchRes = runCatching {
            app.get(searchUrl, headers = headers, timeout = 7).text
        }.getOrNull() ?: return

        val doc = org.jsoup.Jsoup.parse(searchRes)
        val posts = doc.select("article, div.post-item, div.post-content, h2.entry-title a, a[href*='-movie/'], a[href*='-series/']")
        val postLinks = posts.mapNotNull { el ->
            if (el.tagName() == "a") el.attr("href") else el.selectFirst("a")?.attr("href")
        }.distinct().filter { it.isNotBlank() }

        val targetPost = postLinks.firstOrNull() ?: return
        val postHtml = runCatching {
            app.get(targetPost, headers = headers + ("Referer" to searchUrl), timeout = 7).text
        }.getOrNull() ?: return

        val postDoc = org.jsoup.Jsoup.parse(postHtml)
        postDoc.select("a[href*='fastdl.'], a[href*='hubcloud.'], a[href*='vcloud.'], a[href*='gofile.'], a[href*='pixeldrain.']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                runCatching {
                    loadExtractor(href, targetPost, subtitleCallback, callback)
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
            doc.select("a[href*='vcloud.'], a[href*='hubcloud.'], a[href*='fastdl.'], a[href*='fast-dl.'], a[href*='zee-dl.']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && !href.contains("filepress") && !href.contains("filebee") && !href.contains("nexdrive")) {
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
            if (this.posterPath.startsWith("http")) this.posterPath else "https://image.tmdb.org/t/p/w342${this.posterPath}"
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
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
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
