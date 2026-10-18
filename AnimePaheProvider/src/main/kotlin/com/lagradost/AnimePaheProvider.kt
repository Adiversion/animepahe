package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import android.widget.ProgressBar
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
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

        private val cfLock = Any()
        private var lastCfDialogAtMs = 0L
        private val isDialogShowing = AtomicBoolean(false)

        val CHALLENGE_TITLES = listOf(
            "one more step",
            "just a moment",
            "just a moment...",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "cloudflare",
            "security check"
        )

        fun isChallengeTitle(title: String?): Boolean {
            val t = (title ?: "").lowercase().trim()
            return CHALLENGE_TITLES.any { t.contains(it) }
        }

        fun hasClearance(cookies: String?): Boolean {
            if (cookies.isNullOrBlank()) return false
            return cookies.contains("cf_clearance=") && !cookies.contains("cf_clearance=;")
        }

        fun isBlocked(response: NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val text = response.text.trimStart()
            if (text.startsWith("<") && text.contains("<html", ignoreCase = true)) {
                val lower = text.lowercase()
                return lower.contains("<title>just a moment") ||
                       lower.contains("<title>attention required") ||
                       lower.contains("cf-browser-verification") ||
                       lower.contains("challenge-running") ||
                       lower.contains("verify you are human") ||
                       lower.contains("cf-turnstile")
            }
            return false
        }

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

        fun getImageHeaders(url: String? = null): Map<String, String> {
            val ua = cfUserAgent ?: USER_AGENT
            val host = url?.toHttpUrlOrNull()?.host ?: "animepahe.pw"
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Referer" to "$MAIN_URL/",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\""
            )
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie("https://$host")
                    ?: CookieManager.getInstance().getCookie(MAIN_URL)
            }.getOrNull()
            if (!cookies.isNullOrBlank()) {
                headers["Cookie"] = cookies
            }
            return headers
        }

        fun fixImageUrl(url: String?, base: String = MAIN_URL): String? {
            if (url.isNullOrBlank()) return null
            return when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$base$url"
                else -> url
            }
        }

        fun syncCookiesToApp(appInstance: Requests, base: String = MAIN_URL) {
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie(base)
            }.getOrNull() ?: return

            val targetDomains = listOf(
                "animepahe.pw",
                "i.animepahe.pw",
                "animepahe.ru",
                "i.animepahe.ru",
                "animepahe.com",
                "i.animepahe.com",
                "animepahe.org",
                "i.animepahe.org"
            )

            // 1. Sync to Android CookieManager for all subdomains
            val cookieManager = CookieManager.getInstance()
            targetDomains.forEach { domain ->
                cookies.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank()) {
                        runCatching {
                            cookieManager.setCookie("https://$domain", "$trimmed; Domain=.$domain; Path=/")
                            cookieManager.setCookie("https://$domain", "$trimmed; Path=/")
                        }
                    }
                }
            }
            cookieManager.flush()

            // 2. Sync to OkHttp CookieJar for all subdomains
            targetDomains.forEach { domain ->
                val httpUrl = "https://$domain".toHttpUrlOrNull() ?: return@forEach
                val list = ArrayList<Cookie>()
                cookies.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank()) {
                        Cookie.parse(httpUrl, "$trimmed; Domain=$domain; Path=/")?.let { list.add(it) }
                        Cookie.parse(httpUrl, "$trimmed; Path=/")?.let { list.add(it) }
                    }
                }
                if (list.isNotEmpty()) {
                    runCatching {
                        appInstance.baseClient.cookieJar.saveFromResponse(httpUrl, list)
                    }
                }
            }
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

        private fun extractMergedCookies(currentUrl: String?): String {
            val cookieManager = CookieManager.getInstance()
            val map = LinkedHashMap<String, String>()
            val domains = listOfNotNull(
                currentUrl,
                MAIN_URL,
                "https://animepahe.pw/",
                "https://animepahe.org/",
                "https://animepahe.com/",
                "https://challenges.cloudflare.com/",
                "https://cloudflare.com/"
            )
            domains.forEach { d ->
                val cookieStr = runCatching { cookieManager.getCookie(d) }.getOrNull()
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


        @SuppressLint("SetJavaScriptEnabled")
        fun solveCloudflare(appInstance: Requests, targetUrl: String = "$MAIN_URL/api?m=airing&page=1"): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return false
            }
            val activity = CommonActivity.activity ?: getActivity()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Log.e("AnimePahe", "No active Activity found for Cloudflare verification")
                return false
            }

            if (!isDialogShowing.compareAndSet(false, true)) {
                Log.d("AnimePahe", "Verification dialog already in progress")
                return false
            }

            val latch = CountDownLatch(1)
            var success = false

            activity.runOnUiThread {
                try {
                    val density = activity.resources.displayMetrics.density

                    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
                    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                    dialog.setOnShowListener {
                        dialog.window?.apply {
                            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setBackgroundDrawable(ColorDrawable(Color.parseColor("#121218")))
                            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        }
                    }

                    val root = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.parseColor("#121218"))
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setPadding(0, 0, 0, 0)
                    }

                    // Top Header Bar
                    val header = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setBackgroundColor(Color.parseColor("#1E1E2A"))
                        setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val titleTv = TextView(activity).apply {
                        text = "AnimePahe Cloudflare Verification"
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val closeBtn = Button(activity).apply {
                        text = "✕"
                        setTextColor(Color.LTGRAY)
                        setBackgroundColor(Color.TRANSPARENT)
                        textSize = 18f
                        setOnClickListener {
                            dialog.dismiss()
                        }
                    }

                    header.addView(titleTv)
                    header.addView(closeBtn)
                    root.addView(header)

                    val statusText = TextView(activity).apply {
                        text = "Please complete the verification checkmark below. The window will close automatically once done."
                        setTextColor(Color.parseColor("#A0A0B0"))
                        textSize = 12f
                        setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                    }
                    root.addView(statusText)

                    val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (4 * density).toInt()
                        )
                    }
                    root.addView(progressBar)

                    val webView = WebView(activity).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowContentAccess = true
                        allowFileAccess = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        loadsImagesAutomatically = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        val baseUa = userAgentString ?: USER_AGENT
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
                        userAgentString = sanitizedUa
                    }

                    var cookiesSaved = false
                    val handler = Handler(Looper.getMainLooper())
                    var pollElapsedMs = 0L

                    fun saveCookiesAndDismiss(cookies: String) {
                        if (cookiesSaved) return
                        cookiesSaved = true

                        cookieManager.flush()
                        cfCookies = cookies
                        cfUserAgent = webView.settings.userAgentString

                        syncCookiesToApp(appInstance)

                        statusText.text = "✅ Verification successful!"
                        statusText.setTextColor(Color.parseColor("#4CAF50"))
                        progressBar.visibility = View.GONE

                        success = true
                        latch.countDown()

                        handler.postDelayed({
                            runCatching {
                                if (dialog.isShowing) {
                                    dialog.dismiss()
                                }
                            }
                        }, 500L)
                    }

                    val cookiePollRunnable = object : Runnable {
                        override fun run() {
                            if (cookiesSaved || !dialog.isShowing) return
                            pollElapsedMs += 800L
                            val currentCookies = extractMergedCookies(webView.url ?: targetUrl)
                            val currentTitle = webView.title ?: ""

                            Log.d("AnimePahe", "Poll [${pollElapsedMs}ms] title='$currentTitle'")

                            if (hasClearance(currentCookies) && !isChallengeTitle(currentTitle)) {
                                Log.d("AnimePahe", "Clearance confirmed! Saving cookies and dismissing dialog...")
                                saveCookiesAndDismiss(currentCookies)
                                return
                            }

                            if (pollElapsedMs >= 60_000L) {
                                Log.w("AnimePahe", "Turnstile verification timed out after 60s")
                                latch.countDown()
                                runCatching { if (dialog.isShowing) dialog.dismiss() }
                                return
                            }

                            handler.postDelayed(this, 800L)
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            progressBar.progress = newProgress
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            progressBar.visibility = View.GONE
                        }
                    }

                    root.addView(webView)
                    dialog.setContentView(root)

                    dialog.setOnDismissListener {
                        handler.removeCallbacks(cookiePollRunnable)
                        isDialogShowing.set(false)
                        if (!cookiesSaved) {
                            val finalCookies = extractMergedCookies(webView.url ?: targetUrl)
                            val finalTitle = webView.title ?: ""
                            if (hasClearance(finalCookies) && !isChallengeTitle(finalTitle)) {
                                saveCookiesAndDismiss(finalCookies)
                            } else {
                                latch.countDown()
                            }
                        }
                    }

                    webView.loadUrl(targetUrl)
                    dialog.show()
                    handler.postDelayed(cookiePollRunnable, 800L)

                } catch (e: Throwable) {
                    Log.e("AnimePahe", "Failed to display verification dialog: ${e.message}")
                    isDialogShowing.set(false)
                    latch.countDown()
                }
            }

            try {
                latch.await(65, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}

            return success
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
        Log.d("AnimePahe", "fetchWithCloudflare: GET $url")
        // 1. Fast check with active cookies
        val initial = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
        if (initial != null && !isBlocked(initial)) {
            return initial.text
        }

        Log.w("AnimePahe", "Request blocked (code=${initial?.code}). Purging stale cookies and acquiring lock...")
        cfCookies = null

        // 2. Concurrency gate using AtomicBoolean:
        if (isDialogShowing.get()) {
            Log.d("AnimePahe", "Verification dialog already active, waiting for leader thread...")
            var waits = 0
            while (isDialogShowing.get() && waits < 65) {
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {
                    break
                }
                waits++
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastCfDialogAtMs > 3000L) {
                lastCfDialogAtMs = now
                CommonActivity.showToast("Opening AnimePahe Verification...")
                val targetUrl = "$mainUrl/api?m=airing&page=1"
                val solved = solveCloudflare(app, targetUrl)
                Log.d("AnimePahe", "CF bypass dialog completed with result: $solved")
            } else {
                Log.d("AnimePahe", "Dialog recently completed, skipping duplicate invocation")
                try {
                    Thread.sleep(1000L)
                } catch (_: InterruptedException) {}
            }
        }

        // 3. Re-attempt request with newly saved cookies
        val retry = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
        if (retry != null && !isBlocked(retry)) {
            CommonActivity.showToast("Verification successful!")
            return retry.text
        }

        throw ErrorLoadingException("Cloudflare challenge encountered. Please complete the verification checkmark.")
    }

    override val mainPage = listOf(
        MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", false)
    )

    // ── Data Models ──────────────────────────────────────────────────
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    data class MetaAnimeData(
        @JsonProperty("images") val images: List<MetaImage>? = null
    )

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

    // ── Main Page (Airing Releases - Netflix Style Horizontal Lane) ──
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
                val snap = fixImageUrl(item.snapshot)
                this.posterUrl = snap
                this.posterHeaders = getImageHeaders(snap)
                addDubStatus(DubStatus.Subbed, item.episode)
            }
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
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
                val post = fixImageUrl(item.poster)
                this.posterUrl = post
                this.posterHeaders = getImageHeaders(post)
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
                            this.posterUrl = fixImageUrl(ep.snapshot)
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

    // ── Load Anime Details (Spotlight Banner & Metadata) ──────────────
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val session = parseJson<LoadData>(url).let { data ->
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

            var fanartUrl: String? = null
            var highResPosterUrl: String? = null

            val aniZipUrl = when {
                anilistId != null -> "https://api.ani.zip/mappings?anilist_id=$anilistId"
                malId != null -> "https://api.ani.zip/mappings?mal_id=$malId"
                else -> null
            }

            if (aniZipUrl != null) {
                runCatching {
                    val meta = app.get(aniZipUrl).parsedSafe<MetaAnimeData>()
                    fanartUrl = meta?.images?.firstOrNull { 
                        it.coverType.equals("Fanart", ignoreCase = true) || 
                        it.coverType.equals("Banner", ignoreCase = true) ||
                        it.coverType.equals("Background", ignoreCase = true)
                    }?.url
                    highResPosterUrl = meta?.images?.firstOrNull { 
                        it.coverType.equals("Poster", ignoreCase = true) 
                    }?.url
                }
            }

            val genres = doc.select(".anime-genre > ul a").map { it.text() }

            newAnimeLoadResponse(animeTitle, url, tvType) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = highResPosterUrl ?: fixImageUrl(poster)
                this.backgroundPosterUrl = fanartUrl ?: fixImageUrl(episodes.firstOrNull()?.posterUrl ?: poster)
                this.posterHeaders = getImageHeaders(this.posterUrl)
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
