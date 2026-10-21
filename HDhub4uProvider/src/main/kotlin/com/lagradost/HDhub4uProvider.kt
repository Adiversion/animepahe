package com.lagradost

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class HDhub4uProvider : MainAPI() {

    companion object {
        const val DEFAULT_DOMAIN = "https://new8.hdhub4u.cl"
        val OFFICIAL_HOST_ENDPOINTS = listOf(
            "https://h4.suncdn.org/host/",
            "https://points.topapii.com/host/",
            "https://ml.theapii.org/host/",
            "https://cdn.hub4u.cloud/host/"
        )
        const val DOMAIN_SOURCE = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
        const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private var lastSyncTime = 0L
    }

    override var mainUrl = DEFAULT_DOMAIN
    override var name = "HDhub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    /**
     * Synchronize with HDHub4u's official multi-CDN host discovery broadcaster
     * with secondary fallback to remote domain registry.
     */
    private suspend fun syncDomain() {
        if (System.currentTimeMillis() - lastSyncTime < 3600000 && mainUrl != DEFAULT_DOMAIN) return

        // 1. Query official HDHub4u live host endpoints
        for (endpoint in OFFICIAL_HOST_ENDPOINTS) {
            try {
                val res = app.get(endpoint, timeout = 4).parsedSafe<HostResponse>()
                val encodedDomain = res?.c
                if (!encodedDomain.isNullOrBlank()) {
                    val decoded = base64Decode(encodedDomain).substringBefore("?").removeSuffix("/")
                    if (decoded.startsWith("http")) {
                        mainUrl = decoded
                        lastSyncTime = System.currentTimeMillis()
                        return
                    }
                }
            } catch (_: Throwable) {
                // Try next endpoint in cluster
            }
        }

        // 2. Secondary fallback: remote domain registry
        try {
            val res = app.get(DOMAIN_SOURCE, timeout = 5).parsedSafe<Map<String, String>>()
            res?.get("HDHUB4u")?.let { fresh ->
                if (fresh.isNotBlank() && fresh.startsWith("http")) {
                    mainUrl = fresh.removeSuffix("/")
                    lastSyncTime = System.currentTimeMillis()
                }
            }
        } catch (_: Throwable) {
            // Keep default domain if sync unreachable
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest Movies & Series",
        "category/bollywood-movies/" to "Bollywood Movies",
        "category/hollywood-movies/" to "Hollywood Movies",
        "category/south-hindi-movies/" to "South Hindi Dubbed",
        "category/hindi-dubbed/" to "Hindi Dubbed",
        "category/category/web-series/" to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncDomain()
        val url = if (request.data.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}page/$page/"
        }

        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val homeItems = document.select(".recent-movies > li.thumb").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = false
            ),
            hasNext = homeItems.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        syncDomain()
        // Primary search: Pingora Typesense API (HDhub4u official search engine)
        val pingoraResults = runCatching {
            val pingoraUrl = "https://search.pingora.fyi/collections/post/documents/search?q=${URLEncoder.encode(query, "UTF-8")}&query_by=post_title,category,stars,director,imdb_id&query_by_weights=4,2,2,2,4&sort_by=sort_by_date:desc&limit=15&highlight_fields=none&use_cache=true&page=1"
            val res = app.get(
                pingoraUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "Accept" to "application/json, text/plain, */*"
                ),
                timeout = 6
            ).parsedSafe<PingoraSearchResponse>()

            res?.hits?.mapNotNull { hit ->
                val doc = hit.document ?: return@mapNotNull null
                val rawTitle = doc.postTitle ?: return@mapNotNull null
                val permalink = doc.permalink ?: return@mapNotNull null
                val cleanLink = fixUrlNull(permalink.replace(Regex("""https?://[^/]+"""), mainUrl)) ?: return@mapNotNull null
                val cleanT = cleanTitle(rawTitle)
                val poster = doc.postThumbnail
                val quality = getQuality(rawTitle)
                val isTv = rawTitle.contains("Season", ignoreCase = true) ||
                        rawTitle.contains("Series", ignoreCase = true) ||
                        rawTitle.contains("All Episodes", ignoreCase = true)

                if (isTv) {
                    newTvSeriesSearchResponse(cleanT, cleanLink, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                } else {
                    newMovieSearchResponse(cleanT, cleanLink, TvType.Movie) {
                        this.posterUrl = poster
                        this.quality = quality
                    }
                }
            }
        }.getOrNull()

        if (!pingoraResults.isNullOrEmpty()) {
            return pingoraResults
        }

        // Secondary fallback: Native search
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select(".recent-movies > li.thumb").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = selectFirst("figure a, figcaption a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        val titleElem = selectFirst("figcaption p, figcaption a, figure a") ?: linkElem
        val rawTitle = titleElem.text().trim()
        val cleanTitle = cleanTitle(rawTitle)
        val posterUrl = fixUrlNull(selectFirst("figure img, img")?.attr("src"))
        val quality = getQuality(rawTitle)

        val isTv = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("Series", ignoreCase = true) ||
                rawTitle.contains("All Episodes", ignoreCase = true) ||
                href.contains("season", ignoreCase = true)

        return if (isTv) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""^[^\w\s]+"""), "") // Strip leading emojis and symbols like \ue02c
            .replace(
                Regex("""(?i)\b(480p|720p|1080p|2160p|4k|uhd|ds4k|web-dl|webrip|bluray|hdtc|hdtv|x264|hevc|10bit|esub|subs|hindi|dual audio|multi audio|clean|studio dub|dd5\.1|dd2\.0)\b.*"""),
                ""
            )
            .replace(Regex("""[\|\[\]]"""), " ")
            .trim()
    }

    private fun getQuality(title: String): SearchQuality {
        val lower = title.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160p") || lower.contains("ds4k") -> SearchQuality.UHD
            lower.contains("1080p") || lower.contains("720p") -> SearchQuality.HD
            lower.contains("480p") -> SearchQuality.SD
            lower.contains("cam") || lower.contains("hdtc") -> SearchQuality.Cam
            else -> SearchQuality.SD
        }
    }

    override suspend fun load(url: String): LoadResponse {
        syncDomain()
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document

        val rawTitle = doc.selectFirst("h1.page-title")?.text()?.trim() ?: doc.title().trim()
        val cleanTitle = cleanTitle(rawTitle)
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("main.page-body img.aligncenter")?.attr("src")
                ?: doc.selectFirst(".recent-movies li.thumb img")?.attr("src")
        )
        val plot = doc.selectFirst(".page-body p:has(strong:contains(Storyline)), .page-body p:has(strong:contains(Plot))")?.text()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

        val year = Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()

        // Extract IMDb ID for high-definition 16:9 TMDB billboard resolution
        val imdbId = doc.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.let {
            Regex("""(tt\d+)""").find(it)?.groupValues?.get(1)
        }

        var tmdbBackdrop: String? = null
        var tmdbPlot: String? = null

        if (!imdbId.isNullOrBlank()) {
            try {
                val tmdbUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
                val tmdbRes = app.get(tmdbUrl, timeout = 3).parsedSafe<TmdbFindResponse>()
                val movie = tmdbRes?.movieResults?.firstOrNull()
                val tv = tmdbRes?.tvResults?.firstOrNull()
                val backdropPath = movie?.backdropPath ?: tv?.backdropPath
                if (!backdropPath.isNullOrBlank()) {
                    tmdbBackdrop = "https://image.tmdb.org/t/p/original$backdropPath"
                }
                tmdbPlot = movie?.overview ?: tv?.overview
            } catch (_: Throwable) {
                // Fallback gracefully to default poster
            }
        }

        val finalPlot = tmdbPlot?.ifBlank { null } ?: plot
        val finalBackdrop = tmdbBackdrop ?: poster

        // Parse links to differentiate TV Series episodes vs Movies
        val links = doc.select(".page-body a[href]")
        val episodeMap = linkedMapOf<Int, MutableList<String>>()
        val movieLinks = mutableListOf<String>()

        val epRegex = Regex("""(?i)\b(?:EPiSODE|Episode|EP)\s*(\d+)\b""")
        val seasonRegex = Regex("""(?i)\bSeason\s*(\d+)\b""")
        val seasonNumber = seasonRegex.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        for (a in links) {
            val text = a.text().trim()
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.contains("how-to-download") || href.contains("telegram") || href.contains("t.me")) continue

            val epMatch = epRegex.find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toInt()
                episodeMap.getOrPut(epNum) { mutableListOf() }.add(href)
            } else if (text.contains("WATCH", ignoreCase = true) ||
                href.contains("hdstream4u") ||
                href.contains("hubstream") ||
                href.contains("hubcloud") ||
                href.contains("greenmotors") ||
                href.contains("hblinks") ||
                href.contains("hubdrive") ||
                href.contains("hubcdn")
            ) {
                if (episodeMap.isNotEmpty()) {
                    val lastEp = episodeMap.keys.last()
                    episodeMap[lastEp]?.add(href)
                } else {
                    movieLinks.add(href)
                }
            }
        }

        return if (episodeMap.isNotEmpty()) {
            val episodes = episodeMap.map { (epNum, epLinks) ->
                newEpisode(epLinks.joinToString("||")) {
                    this.name = "Episode $epNum"
                    this.season = seasonNumber
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = year
                if (imdbId != null) addImdbId(imdbId)
            }
        } else {
            val moviePayload = if (movieLinks.isNotEmpty()) movieLinks.joinToString("||") else url
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, moviePayload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = finalBackdrop
                this.plot = finalPlot
                this.year = year
                if (imdbId != null) addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = if (data.contains("||")) {
            data.split("||").map { it.trim() }.filter { it.isNotBlank() }
        } else if (data.startsWith("http")) {
            listOf(data)
        } else {
            emptyList()
        }

        val allCandidateUrls = mutableListOf<String>()

        for (candidate in rawLinks) {
            when {
                candidate.contains("greenmotors") || candidate.contains("?id=") -> {
                    // Unpack shortener token into actual destination links
                    val resolved = unpackShortener(candidate)
                    allCandidateUrls.addAll(resolved)
                }
                candidate.contains("hblinks") -> {
                    val resolved = scrapeHblinks(candidate)
                    allCandidateUrls.addAll(resolved)
                }
                else -> {
                    allCandidateUrls.add(candidate)
                }
            }
        }

        // Parallel resolution of all stream sources using coroutines
        coroutineScope {
            allCandidateUrls.distinct().map { streamUrl ->
                async {
                    safeApiCall {
                        when {
                            streamUrl.contains("hdstream4u") || streamUrl.contains("hubstream") -> {
                                extractHdstream4u(streamUrl, subtitleCallback, callback)
                            }
                            streamUrl.contains("hubcloud") -> {
                                extractHubcloud(streamUrl, subtitleCallback, callback)
                            }
                            streamUrl.contains("pixeldrain.dev") || streamUrl.contains("pixeldrain.com") -> {
                                extractPixeldrain(streamUrl, callback)
                            }
                            streamUrl.contains("fuckingfast.net") -> {
                                extractBuzzServer(streamUrl, callback)
                            }
                            else -> {
                                loadExtractor(streamUrl, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }

    /**
     * Reverse-engineered shortener bypass: Base64 -> Base64 -> ROT13 -> Base64 -> JSON payload.
     */
    private suspend fun unpackShortener(shortenerUrl: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val res = app.get(shortenerUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            val tokenMatch = Regex("""s\('o','([A-Za-z0-9+/=]+)'""").find(res)
                ?: Regex("""ck\('_wp_http_\d+','([^']+)'""").find(res)

            val token = tokenMatch?.groupValues?.get(1) ?: return emptyList()

            // Step 1: Base64 decode
            val step1 = base64Decode(token)
            // Step 2: Base64 decode
            val step2 = base64Decode(step1)
            // Step 3: ROT13 decode
            val step3 = rot13(step2)
            // Step 4: Base64 decode to JSON
            val step4 = base64Decode(step3)

            val json = JSONObject(step4)
            val encodedTarget = json.optString("o")
            if (encodedTarget.isNotBlank()) {
                val finalUrl = base64Decode(encodedTarget).trim()
                if (finalUrl.startsWith("http")) {
                    if (finalUrl.contains("hblinks")) {
                        results.addAll(scrapeHblinks(finalUrl))
                    } else {
                        results.add(finalUrl)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("HDhub4u", "Unpack shortener error: ${e.message}")
        }
        return results
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun base64Decode(str: String): String {
        return String(Base64.decode(str.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }

    private suspend fun scrapeHblinks(hblinksUrl: String): List<String> {
        val links = mutableListOf<String>()
        try {
            val doc = app.get(
                hblinksUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://greenmotors.cc/"
                )
            ).document

            doc.select("h3 a, h5 a, div.entry-content p a, div.entry-content a").forEach { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http")) {
                    links.add(href)
                }
            }
        } catch (e: Throwable) {
            Log.e("HDhub4u", "Scrape hblinks error: ${e.message}")
        }
        return links
    }

    /**
     * Unpack Dean Edwards javascript on hdstream4u to extract direct HLS master playlists.
     */
    private suspend fun extractHdstream4u(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text
        val unpacked = unpackDeanEdwards(html) ?: html

        val masterUrl = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpacked)?.value
        if (!masterUrl.isNullOrBlank()) {
            M3u8Helper.generateM3u8(
                source = "HDStream4u",
                streamUrl = masterUrl,
                referer = url,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).forEach(callback)
        }
    }

    private fun unpackDeanEdwards(script: String): String? {
        val regex = Regex("""}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""")
        val match = regex.find(script) ?: return null
        val (p, aStr, cStr, kStr) = match.destructured
        val a = aStr.toIntOrNull() ?: return null
        val c = cStr.toIntOrNull() ?: return null
        val k = kStr.split("|")

        fun baseN(num: Int, base: Int): String {
            val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.insert(0, digits[n % base])
                n /= base
            }
            return if (sb.isEmpty()) "0" else sb.toString()
        }

        val dict = HashMap<String, String>()
        for (i in 0 until c) {
            val key = if (i >= 10) baseN(i, a) else i.toString()
            val word = if (i < k.size && k[i].isNotEmpty()) k[i] else key
            dict[key] = word
        }

        return Regex("""\b\w+\b""").replace(p) { m ->
            dict[m.value] ?: m.value
        }
    }

    /**
     * HubCloud resolution extracting Cloudflare R2 direct links, Pixeldrain, BuzzServer, and 10Gbps streams.
     */
    private suspend fun extractHubcloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val driveDoc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val phpBtn = driveDoc.selectFirst("a[href*='hubcloud.php']")?.attr("href") ?: return
        val phpUrl = fixUrl(phpBtn)

        val phpDoc = app.get(
            phpUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url
            )
        ).document

        phpDoc.select("a.btn, a[href*='download'], div.card-body a").forEach { btn ->
            val href = btn.attr("href").trim()

            when {
                // Cloudflare R2 direct high-speed video link
                href.contains("cloudflarestorage.com") -> {
                    callback(
                        ExtractorLink(
                            source = "HubCloud R2 (Fast)",
                            name = "HubCloud FSL [Direct]",
                            url = href,
                            referer = "",
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
                // 10Gbps PixelServer
                href.contains("pixel.hubcloud") -> {
                    callback(
                        ExtractorLink(
                            source = "HubCloud 10Gbps",
                            name = "HubCloud 10Gbps [Direct]",
                            url = href,
                            referer = phpUrl,
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
                // Pixeldrain mirror
                href.contains("pixeldrain") -> {
                    extractPixeldrain(href, callback)
                }
                // BuzzServer (fuckingfast.net)
                href.contains("fuckingfast.net") -> {
                    extractBuzzServer(href, callback)
                }
                // WebPlayer proxy
                href.contains("hbplay.pages.dev") && href.contains("?u=") -> {
                    try {
                        val encoded = href.substringAfter("?u=").substringBefore("&")
                        val decodedUrl = base64Decode(encoded)
                        if (decodedUrl.startsWith("http")) {
                            callback(
                                ExtractorLink(
                                    source = "HubCloud WebPlayer",
                                    name = "HubCloud Stream [Fast]",
                                    url = decodedUrl,
                                    referer = "",
                                    quality = Qualities.P1080.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    } catch (_: Throwable) {
                        // ignore malformed proxy payload
                    }
                }
            }
        }
    }

    private suspend fun extractPixeldrain(url: String, callback: (ExtractorLink) -> Unit) {
        val fileId = Regex("""/u/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://pixeldrain.dev/api/file/$fileId"
        callback(
            ExtractorLink(
                source = "Pixeldrain",
                name = "Pixeldrain [Fast]",
                url = directUrl,
                referer = "https://pixeldrain.com/",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO
            )
        )
    }

    private suspend fun extractBuzzServer(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val directVideo = doc.selectFirst("video source, a.btn-download")?.attr("src")
            ?: doc.selectFirst("a[href*='.mp4'], a[href*='.mkv']")?.attr("href")
        if (!directVideo.isNullOrBlank()) {
            callback(
                ExtractorLink(
                    source = "BuzzServer",
                    name = "BuzzServer [Direct]",
                    url = directVideo,
                    referer = url,
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }

    data class TmdbFindResponse(
        @JsonProperty("movie_results") val movieResults: List<TmdbItem>? = null,
        @JsonProperty("tv_results") val tvResults: List<TmdbItem>? = null
    )

    data class TmdbItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null
    )

    data class HostResponse(
        @JsonProperty("h") val h: String? = null,
        @JsonProperty("c") val c: String? = null,
        @JsonProperty("t") val t: Long? = null
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

