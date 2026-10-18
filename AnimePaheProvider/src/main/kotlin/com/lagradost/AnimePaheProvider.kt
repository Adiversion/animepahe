package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.math.pow

class AnimePaheProvider : MainAPI() {

    companion object {
        const val MAIN_URL = "https://animepahe.pw"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        val MIRRORS = listOf(
            "https://animepahe.pw",
            "https://animepahe.org",
            "https://animepahe.com",
            "https://animepahe.ru"
        )

        var cfCookies: String? = null

        fun getHeaders(base: String = MAIN_URL): Map<String, String> {
            val headers = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "$base/",
                "X-Requested-With" to "XMLHttpRequest"
            )
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie(base)
            }.getOrNull()
            if (!cookies.isNullOrBlank()) {
                headers["Cookie"] = cookies
            }
            return headers
        }

        private fun getActivity(): Activity? {
            return CommonActivity.activity ?: runCatching {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val mActivitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                val activities = mActivitiesField.get(activityThread) as? Map<*, *>
                activities?.values?.firstNotNullOfOrNull { record ->
                    val pausedField = record.javaClass.getDeclaredField("paused").apply { isAccessible = true }
                    val isPaused = pausedField.getBoolean(record)
                    if (!isPaused) {
                        val activityField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                        activityField.get(record) as? Activity
                    } else null
                }
            }.getOrNull()
        }

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun solveCloudflare(targetUrl: String = MAIN_URL): String? {
            val activity = getActivity() ?: return null
            val deferred = CompletableDeferred<String?>()
            val handler = Handler(Looper.getMainLooper())

            handler.post {
                try {
                    val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#121212"))
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }

                    // Top Header Bar
                    val topBar = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setBackgroundColor(Color.parseColor("#1E1E1E"))
                        setPadding(32, 24, 32, 24)
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val titleText = TextView(activity).apply {
                        text = "AnimePahe Cloudflare Verification"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }

                    val closeButton = Button(activity).apply {
                        text = "Close"
                        setTextColor(Color.LTGRAY)
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener {
                            dialog.dismiss()
                        }
                    }

                    topBar.addView(titleText)
                    topBar.addView(closeButton)
                    layout.addView(topBar)

                    val infoText = TextView(activity).apply {
                        text = "Please complete the verification checkmark below. This window will close automatically."
                        setTextColor(Color.parseColor("#9E9E9E"))
                        textSize = 12f
                        setPadding(32, 16, 32, 16)
                    }
                    layout.addView(infoText)

                    val webView = WebView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)

                    fun checkForClearance(url: String?) {
                        val currentCookies = cookieManager.getCookie(url ?: targetUrl) ?: ""
                        if (currentCookies.contains("cf_clearance")) {
                            cfCookies = currentCookies
                            if (deferred.isActive) {
                                deferred.complete(currentCookies)
                            }
                            handler.post {
                                runCatching { dialog.dismiss() }
                            }
                        }
                    }

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = USER_AGENT
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            checkForClearance(url)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            super.onLoadResource(view, url)
                            checkForClearance(url)
                        }
                    }

                    val checkRunnable = object : Runnable {
                        override fun run() {
                            if (deferred.isActive) {
                                checkForClearance(targetUrl)
                                handler.postDelayed(this, 1000L)
                            }
                        }
                    }
                    handler.postDelayed(checkRunnable, 1000L)

                    dialog.setContentView(layout)
                    dialog.setOnDismissListener {
                        handler.removeCallbacks(checkRunnable)
                        if (deferred.isActive) {
                            val finalCookies = cookieManager.getCookie(targetUrl)
                            if (finalCookies?.contains("cf_clearance") == true) {
                                cfCookies = finalCookies
                                deferred.complete(finalCookies)
                            } else {
                                deferred.complete(null)
                            }
                        }
                    }

                    webView.loadUrl(targetUrl)
                    dialog.show()
                } catch (e: Throwable) {
                    deferred.complete(null)
                }
            }

            return withTimeoutOrNull(75_000L) {
                deferred.await()
            }
        }

        private fun getType(t: String): TvType {
            return when {
                t.contains("OVA", ignoreCase = true) || t.contains("Special", ignoreCase = true) -> TvType.OVA
                t.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val YTSM = Regex("ysmm = '([^']+)")
        val KWIK_PARAMS_RE = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        val KWIK_D_URL = Regex("""action="([^"]+)"""")
        val KWIK_D_TOKEN = Regex("""value="([^"]+)"""")
        val YOUTUBE_VIDEO_LINK =
            Regex("""(^(?:https?:)?(?://)?(?:www\.)?(?:youtu\.be/|youtube(?:-nocookie)?\.(?:[A-Za-z]{2,4}|[A-Za-z]{2,3}\.[A-Za-z]{2})/)(?:watch|embed/|vi?/)*(?:\?[\w=&]*vi?=)?[^#&?/]{11}.*$)""")

        fun decodeBase64Safe(str: String): String {
            return try {
                String(Base64.decode(str, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                str
            }
        }
    }

    override var mainUrl = MAIN_URL
    override var name = "AnimePahe"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    private suspend fun fetchWithCloudflare(url: String): String {
        // 1. Try direct request with current cookies
        val direct = try {
            app.get(url, headers = getHeaders())
        } catch (e: Throwable) {
            null
        }

        if (direct != null && direct.code == 200 && !direct.text.contains("Just a moment...") && !direct.text.contains("challenges.cloudflare.com")) {
            return direct.text
        }

        // 2. If blocked, open in-app visual WebView dialog for interactive Cloudflare verification
        val solvedCookie = solveCloudflare(MAIN_URL)
        if (!solvedCookie.isNullOrBlank()) {
            val retry = app.get(url, headers = getHeaders())
            if (retry.code == 200 && !retry.text.contains("Just a moment...") && !retry.text.contains("challenges.cloudflare.com")) {
                return retry.text
            }
        }

        throw ErrorLoadingException("Cloudflare challenge encountered. Please complete the verification checkmark.")
    }


    override val mainPage = listOf(
        MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", true)
    )

    // ── Data Models ──────────────────────────────────────────────────
    data class AiringItem(
        @JsonProperty("anime_title") val animeTitle: String,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("snapshot") val snapshot: String?,
        @JsonProperty("created_at") val createdAt: String?,
        @JsonProperty("anime_session") val animeSession: String
    )

    data class AnimePaheLatestReleases(
        @JsonProperty("total") val total: Int,
        @JsonProperty("data") val data: List<AiringItem>
    )

    data class AnimePaheSearchData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("year") val year: Int?,
        @JsonProperty("score") val score: Double?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("session") val session: String,
        @JsonProperty("relevance") val relevance: String?
    )

    data class AnimePaheSearch(
        @JsonProperty("total") val total: Int,
        @JsonProperty("data") val data: List<AnimePaheSearchData>
    )

    data class AnimeEpisodeData(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("anime_id") val animeId: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("snapshot") val snapshot: String?,
        @JsonProperty("session") val session: String,
        @JsonProperty("filler") val filler: Int?,
        @JsonProperty("created_at") val createdAt: String?
    )

    data class AnimePaheReleaseResponse(
        @JsonProperty("total") val total: Int,
        @JsonProperty("per_page") val perPage: Int,
        @JsonProperty("current_page") val currentPage: Int,
        @JsonProperty("last_page") val lastPage: Int,
        @JsonProperty("data") val data: List<AnimeEpisodeData>
    )

    data class LoadData(val session: String, val sessionDate: Long, val name: String)

    data class LinkLoadData(
        @JsonProperty("mainUrl") val mainUrl: String,
        @JsonProperty("is_play_page") val isPlayPage: Boolean,
        @JsonProperty("episode_num") val episodeNum: Int,
        @JsonProperty("page") val page: Int,
        @JsonProperty("session") val session: String,
        @JsonProperty("episode_session") val episodeSession: String
    ) {
        suspend fun getUrl(): String? {
            return if (isPlayPage) {
                "$mainUrl/play/$session/$episodeSession"
            } else {
                val url = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=$page"
                val jsonResponse = app.get(
                    url,
                    headers = getHeaders(mainUrl)
                ).parsedSafe<AnimePaheReleaseResponse>() ?: return null
                val epSession = jsonResponse.data.firstOrNull { it.episode == episodeNum }?.session
                    ?: return null
                "$mainUrl/play/$session/$epSession"
            }
        }
    }

    // ── Main Page (Airing Releases) ───────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val responseText = fetchWithCloudflare(url)
        val parsed = parseJson<AnimePaheLatestReleases>(responseText)

        val items = parsed.data.map { item ->
            newAnimeSearchResponse(
                item.animeTitle,
                LoadData(item.animeSession, unixTime, item.animeTitle).toJson(),
                fix = false
            ) {
                this.posterUrl = item.snapshot
                addDubStatus(DubStatus.Subbed, item.episode)
            }
        }

        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = items.isNotEmpty()
        )
    }

    // ── Search ────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api?m=search&l=8&q=$encodedQuery"
        val responseText = fetchWithCloudflare(url)
        val parsed = parseJson<AnimePaheSearch>(responseText)

        return parsed.data.map { item ->
            newAnimeSearchResponse(
                item.title,
                LoadData(item.session, unixTime, item.title).toJson(),
                fix = false
            ) {
                this.posterUrl = item.poster
                addDubStatus(DubStatus.Subbed, item.episodes)
            }
        }
    }

    // ── Episode List Generation ───────────────────────────────────────
    private suspend fun generateListOfEpisodes(session: String): List<Episode> {
        return try {
            val uri = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=1"
            val responseText = fetchWithCloudflare(uri)
            val data = parseJson<AnimePaheReleaseResponse>(responseText)

            val lastPage = data.lastPage
            val perPage = data.perPage
            val total = data.total
            val episodes = ArrayList<Episode>()

            fun getTitle(k: AnimeEpisodeData): String {
                val title = k.title
                return if (!title.isNullOrBlank()) title else "Episode ${k.episode ?: 1}"
            }

            if (lastPage == 1 && perPage >= total) {
                data.data.forEach { ep ->
                    episodes.add(
                        newEpisode(
                            LinkLoadData(
                                mainUrl = mainUrl,
                                isPlayPage = true,
                                episodeNum = ep.episode ?: 0,
                                page = 1,
                                session = session,
                                episodeSession = ep.session
                            ).toJson()
                        ) {
                            ep.createdAt?.let { addDate(it) }
                            this.name = getTitle(ep)
                            this.posterUrl = ep.snapshot
                            this.episode = ep.episode
                        }
                    )
                }
            } else {
                var currentEp = 1
                for (page in 1..lastPage) {
                    for (i in 0 until perPage) {
                        if (currentEp <= total) {
                            episodes.add(
                                newEpisode(
                                    LinkLoadData(
                                        mainUrl = mainUrl,
                                        isPlayPage = false,
                                        episodeNum = currentEp,
                                        page = page,
                                        session = session,
                                        episodeSession = ""
                                    ).toJson()
                                ) {
                                    this.episode = currentEp
                                    this.name = "Episode $currentEp"
                                }
                            )
                            currentEp++
                        }
                    }
                }
            }
            episodes
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Load Anime Details ────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val session = parseJson<LoadData>(url).let { data ->
                // If cached session is older than 10 minutes, re-query search to get fresh session ID
                if (data.sessionDate + 60 * 10 < unixTime) {
                    val fresh = search(data.name).firstOrNull() ?: return@let null
                    parseJson<LoadData>(fresh.url).session
                } else {
                    data.session
                }
            } ?: return null

            val pageHtml = fetchWithCloudflare("$mainUrl/anime/$session")
            val doc = Jsoup.parse(pageHtml)

            val japTitle = doc.selectFirst("h2.japanese")?.text()
            val animeTitle = doc.selectFirst("span.sr-only.unselectable")?.text()
                ?: doc.selectFirst("h1")?.text() ?: ""
            val poster = doc.selectFirst(".anime-poster a")?.attr("href")

            val tvTypeStr = doc.selectFirst("""a[href*="/anime/type/"]""")?.text() ?: ""
            val tvType = getType(tvTypeStr)

            val trailer: String? = if (pageHtml.contains("youtube.com/watch")) {
                YOUTUBE_VIDEO_LINK.find(pageHtml)?.groupValues?.get(1)
            } else null

            val episodes = generateListOfEpisodes(session)
            val year = Regex("""<strong>Aired:</strong>[^,]*, (\d+)""")
                .find(pageHtml)?.groupValues?.get(1)?.toIntOrNull()

            val status = when {
                doc.selectFirst("a[href='/anime/airing']") != null -> ShowStatus.Ongoing
                doc.selectFirst("a[href='/anime/completed']") != null -> ShowStatus.Completed
                else -> null
            }

            val synopsis = doc.selectFirst(".anime-synopsis")?.text()

            var anilistId: Int? = null
            var malId: Int? = null

            doc.select(".external-links > a").forEach { aTag ->
                val href = aTag.attr("href")
                val lastSegment = href.trimEnd('/').substringAfterLast('/')
                if (href.contains("anilist.co")) {
                    anilistId = lastSegment.toIntOrNull()
                } else if (href.contains("myanimelist.net")) {
                    malId = lastSegment.toIntOrNull()
                }
            }

            val genres = doc.select(".anime-genre > ul a").map { it.text() }

            newAnimeLoadResponse(animeTitle, url, tvType) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = poster
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                this.showStatus = status
                this.plot = synopsis
                this.tags = if (genres.isNotEmpty()) ArrayList(genres) else null

                addMalId(malId)
                addAniListId(anilistId)
                addTrailer(trailer)
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    // ── Kwik Stream Unpacker & Pahe Decryptor ──────────────────────────
    private suspend fun getStreamUrlFromKwik(kwikUrl: String): String? {
        val response = app.get(
            kwikUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text

        val unpacked = getAndUnpack(response)
        return Regex("""source='(.*?)'""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(?:https?://[^\s'"<>]+\.(?:mp4|m3u8)[^\s'"<>]*)""").find(unpacked)?.value
    }

    private fun paheDecrypt(fullString: String, key: String, v1: Int, v2: Int): String? {
        if (key.isEmpty() || v2 !in 2..36) return null
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val toFind = key.getOrNull(v2) ?: return null
        val sb = StringBuilder()
        var i = 0
        return runCatching {
            while (i < fullString.length) {
                val nextIndex = fullString.indexOf(toFind, i)
                if (nextIndex == -1) break
                var decodedCharStr = ""
                for (j in i until nextIndex) {
                    decodedCharStr += keyIndexMap[fullString[j]] ?: return null
                }
                if (decodedCharStr.isEmpty()) return null
                sb.append((decodedCharStr.toInt(v2) - v1).toChar())
                i = nextIndex + 1
            }
            sb.toString()
        }.getOrNull()
    }

    // ── Load Links ────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseJson<LinkLoadData>(data)
        val episodeUrl = parsed.getUrl() ?: return false

        val html = fetchWithCloudflare(episodeUrl)
        val doc = Jsoup.parse(html)

        // 1. Resolution menu buttons (#resolutionMenu button[data-src])
        val buttons = doc.select("#resolutionMenu > button")
        for (btn in buttons) {
            val kwikSrc = btn.attr("data-src").takeIf { it.isNotBlank() } ?: continue
            val qualityText = btn.text()
            val quality = Regex("""(\d+)p""").find(qualityText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            getStreamUrlFromKwik(kwikSrc)?.let { streamUrl ->
                callback(
                    newExtractorLink(
                        source = "AnimePahe",
                        name = "AnimePahe - $qualityText",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://kwik.cx/"
                        this.quality = quality
                    }
                )
            }
        }

        // 2. Embedded player fallback script regex: let url = "https://kwik.cx/e/..."
        if (buttons.isEmpty()) {
            val urlRegex = Regex("""let url = "(.*?)";""")
            val embed = urlRegex.find(html)?.groupValues?.getOrNull(1)
            if (!embed.isNullOrBlank()) {
                getStreamUrlFromKwik(embed)?.let { link ->
                    callback(
                        newExtractorLink(
                            source = "AnimePahe",
                            name = "Kwik",
                            url = link,
                            type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://kwik.cx/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }

        // 3. Download links from #pickDownload
        val downloadAnchors = doc.select("#pickDownload > a")
        for (a in downloadAnchors) {
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
            val label = a.text()
            val quality = Regex("""(\d+)p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            if (href.contains("kwik.cx")) {
                getStreamUrlFromKwik(href)?.let { directUrl ->
                    callback(
                        newExtractorLink(
                            source = "AnimePahe",
                            name = "Download - $label",
                            url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = href
                            this.quality = quality
                        }
                    )
                }
            }
        }

        return true
    }
}
