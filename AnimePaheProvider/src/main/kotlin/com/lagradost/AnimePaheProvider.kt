package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        var cfUserAgent: String? = null

        fun getHeaders(base: String = MAIN_URL): Map<String, String> {
            val ua = cfUserAgent ?: USER_AGENT
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "$base/",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
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
                    if (record == null) return@firstNotNullOfOrNull null
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
        fun solveCloudflare(targetUrl: String = MAIN_URL): String? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return null
            }
            val activity = getActivity()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                android.util.Log.e("AnimePahe", "No active Activity found for Cloudflare verification")
                return null
            }
            cfCookies = null
            val latch = CountDownLatch(1)
            var solvedCookies: String? = null
            val handler = Handler(Looper.getMainLooper())

            activity.runOnUiThread {
                try {
                    val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                    dialog.window?.apply {
                        setLayout(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundDrawable(ColorDrawable(Color.parseColor("#121212")))
                        decorView.setPadding(0, 0, 0, 0)
                        setFlags(
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                        )
                        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    }

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
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()

                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowContentAccess = true
                        allowFileAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        loadsImagesAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Sanitize User-Agent to avoid WebView bot detection
                    val baseUa = webView.settings.userAgentString ?: USER_AGENT
                    val sanitizedUa = baseUa
                        .replace("; wv", "")
                        .replace(Regex("Version/[0-9.]+\\s*"), "")
                        .replace("Android TV", "Android")
                        .let { ua ->
                            if (ua.contains("Android") && ua.contains("Safari/") && !ua.contains("Mobile Safari/")) {
                                ua.replace("Safari/", "Mobile Safari/")
                            } else {
                                ua
                            }
                        }
                    webView.settings.userAgentString = sanitizedUa
                    cfUserAgent = sanitizedUa

                    fun isChallenge(title: String?, url: String?): Boolean {
                        val t = (title ?: "").lowercase().trim()
                        val u = (url ?: "").lowercase().trim()

                        // 1. Still on Cloudflare challenge host or path
                        if (u.contains("challenges.cloudflare.com") || u.contains("/cdn-cgi/challenge-platform")) {
                            return true
                        }

                        // 2. Challenge title keywords (reverse-engineered from Phisher)
                        val challengeTitles = listOf(
                            "just a moment",
                            "one more step",
                            "checking your browser",
                            "attention required",
                            "ddos-guard",
                            "cloudflare",
                            "security check",
                            "challenge"
                        )
                        if (challengeTitles.any { t.contains(it) }) {
                            return true
                        }

                        // 3. Initial loading state
                        if (t.isEmpty() && (u.isEmpty() || u == "about:blank")) {
                            return true
                        }

                        return false
                    }

                    fun extractMergedCookies(currentUrl: String?): String {
                        val map = LinkedHashMap<String, String>()
                        val domains = listOfNotNull(
                            currentUrl,
                            "https://animepahe.pw/",
                            "https://animepahe.org/",
                            "https://animepahe.com/",
                            "https://challenges.cloudflare.com/",
                            "https://cloudflare.com/"
                        )
                        domains.forEach { d ->
                            val cookieStr = cookieManager.getCookie(d)
                            if (!cookieStr.isNullOrBlank()) {
                                cookieStr.split(";").forEach { part ->
                                    val trimmed = part.trim()
                                    val eq = trimmed.indexOf("=")
                                    if (eq > 0) {
                                        val k = trimmed.substring(0, eq).trim()
                                        val v = trimmed.substring(eq + 1).trim()
                                        if (k.isNotEmpty() && v.isNotEmpty()) {
                                            map[k] = v
                                        }
                                    }
                                }
                            }
                        }
                        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    }

                    val autoCheckScript = """
                        (function() {
                            try {
                                var selectors = [
                                    'input[type="checkbox"]',
                                    '.ctp-checkbox-label',
                                    '#challenge-stage input',
                                    '#cf-turnstile input',
                                    '.cf-turnstile input',
                                    '#turnstile-wrapper input'
                                ];
                                for (var i = 0; i < selectors.length; i++) {
                                    var el = document.querySelector(selectors[i]);
                                    if (el && !el.checked) {
                                        el.click();
                                        return;
                                    }
                                }
                                var frames = document.querySelectorAll('iframe');
                                for (var f = 0; f < frames.length; f++) {
                                    try {
                                        var doc = frames[f].contentDocument || frames[f].contentWindow.document;
                                        if (doc) {
                                            for (var j = 0; j < selectors.length; j++) {
                                                var cel = doc.querySelector(selectors[j]);
                                                if (cel && !cel.checked) {
                                                    cel.click();
                                                    return;
                                                }
                                            }
                                        }
                                    } catch(err) {}
                                }
                            } catch(e) {}
                        })();
                    """.trimIndent()

                    fun tryAutoCheck() {
                        runCatching {
                            webView.evaluateJavascript(autoCheckScript, null)
                        }
                    }

                    fun checkForClearance(url: String?) {
                        val title = webView.title ?: ""
                        val currentUrl = webView.url ?: url ?: ""

                        // 1. Immediately check if cf_clearance has been issued
                        val cookies = extractMergedCookies(currentUrl)
                        if (cookies.contains("cf_clearance")) {
                            android.util.Log.d("AnimePahe", "Clearance verified! Title='$title', saving cookies and dismissing dialog.")
                            cookieManager.flush()
                            cfCookies = cookies
                            solvedCookies = cookies
                            cfUserAgent = webView.settings.userAgentString

                            handler.post {
                                infoText.text = "✅ Verification successful! Loading catalog..."
                                infoText.setTextColor(Color.parseColor("#4CAF50"))
                            }

                            latch.countDown()
                            handler.postDelayed({
                                runCatching {
                                    if (dialog.isShowing) {
                                        dialog.dismiss()
                                    }
                                }
                            }, 800L)
                            return
                        }

                        // 2. While Cloudflare challenge is active, attempt auto-trigger
                        if (isChallenge(title, currentUrl)) {
                            android.util.Log.d("AnimePahe", "Challenge active: title='$title', url='$currentUrl'")
                            tryAutoCheck()
                            return
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress > 50) {
                                tryAutoCheck()
                                checkForClearance(view?.url)
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            tryAutoCheck()
                            checkForClearance(url)
                        }
                    }

                    val checkRunnable = object : Runnable {
                        override fun run() {
                            if (latch.count > 0) {
                                tryAutoCheck()
                                checkForClearance(webView.url ?: targetUrl)
                                handler.postDelayed(this, 800L)
                            }
                        }
                    }
                    handler.postDelayed(checkRunnable, 800L)

                    dialog.setContentView(layout)
                    dialog.setOnDismissListener {
                        handler.removeCallbacks(checkRunnable)
                        if (latch.count > 0) {
                            val currentUrl = webView.url ?: targetUrl
                            val finalCookies = extractMergedCookies(currentUrl)
                            if (finalCookies.contains("cf_clearance")) {
                                cookieManager.flush()
                                cfCookies = finalCookies
                                solvedCookies = finalCookies
                                cfUserAgent = webView.settings.userAgentString
                            }
                            latch.countDown()
                        }
                    }

                    webView.loadUrl(targetUrl)
                    dialog.show()
                } catch (e: Throwable) {
                    android.util.Log.e("AnimePahe", "Failed to display verification dialog", e)
                    CommonActivity.showToast("Verification dialog error: ${e.message}")
                    latch.countDown()
                }
            }

            latch.await(90, TimeUnit.SECONDS)
            return solvedCookies
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
        android.util.Log.d("AnimePahe", "fetchWithCloudflare: GET $url")
        // 1. Try direct request with current cookies
        val direct = try {
            app.get(url, headers = getHeaders())
        } catch (e: Throwable) {
            android.util.Log.e("AnimePahe", "Direct request failed with exception", e)
            null
        }

        if (direct != null && direct.code == 200 && !direct.text.contains("Just a moment...") && !direct.text.contains("challenges.cloudflare.com")) {
            android.util.Log.d("AnimePahe", "Direct request succeeded (200 OK)")
            return direct.text
        }

        android.util.Log.w("AnimePahe", "Direct request blocked (code=${direct?.code}). Launching Turnstile solver...")
        // 2. If blocked, notify user and open in-app visual WebView dialog for interactive Cloudflare verification
        CommonActivity.showToast("Opening AnimePahe Verification...")
        val solvedCookie = solveCloudflare(url)
        if (!solvedCookie.isNullOrBlank()) {
            android.util.Log.d("AnimePahe", "Retrying request to $url with solved cookies...")
            val retry = app.get(url, headers = getHeaders())
            if (retry.code == 200 && !retry.text.contains("Just a moment...") && !retry.text.contains("challenges.cloudflare.com")) {
                CommonActivity.showToast("Verification successful!")
                android.util.Log.d("AnimePahe", "Retry succeeded (200 OK)")
                return retry.text
            }
            android.util.Log.e("AnimePahe", "Retry failed: code=${retry.code}")
        } else {
            android.util.Log.w("AnimePahe", "solveCloudflare returned null or blank")
        }

        throw ErrorLoadingException("Cloudflare challenge encountered. Please complete the verification checkmark.")
    }


    override val mainPage = listOf(
        MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", false)
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
                "User-Agent" to (cfUserAgent ?: USER_AGENT),
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
