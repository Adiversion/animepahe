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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

        var cfCookies: String? = null
        var cfUserAgent: String? = null

        private var lastCfDialogAtMs = 0L
        private val isDialogShowing = AtomicBoolean(false)

        val CHALLENGE_TITLES = listOf(
            "one more step",
            "just a moment",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "cloudflare",
            "security check",
            "5 seconds",
            "turnstile",
            "wait a moment",
            "verifying"
        )

        fun isChallengeTitle(title: String?): Boolean {
            val t = (title ?: "").lowercase().trim()
            if (t.isEmpty() || t == "about:blank") return true
            return CHALLENGE_TITLES.any { t.contains(it) }
        }

        fun extractClearanceToken(cookies: String?): String? {
            if (cookies.isNullOrBlank()) return null
            val match = Regex("""cf_clearance=([^;]+)""").find(cookies)
            val token = match?.groupValues?.get(1)?.trim()
            return if (!token.isNullOrBlank() && token != ";") token else null
        }

        fun hasClearance(cookies: String?): Boolean {
            return extractClearanceToken(cookies) != null
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
            if (cfUserAgent == null) {
                val act = CommonActivity.activity ?: getActivity()
                if (act != null) {
                    runCatching {
                        val defaultUa = WebSettings.getDefaultUserAgent(act)
                        if (!defaultUa.isNullOrBlank()) {
                            cfUserAgent = defaultUa
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
                        }
                    }
                }
            }
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

        private val KEY_REGEX = Regex("""^#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:\s*)$""")
        private val SEGMENT_REGEX = Regex("""segment-(\d+)-""")

        fun fixHlsAesSequenceIvs(m3u8: String): String {
            var currentKeyUri: String? = null
            val sb = java.lang.StringBuilder(m3u8.length)

            for (line in m3u8.lines()) {
                val trimmed = line.trim()
                val keyMatch = KEY_REGEX.find(trimmed)
                if (keyMatch != null) {
                    currentKeyUri = keyMatch.groupValues[1]
                    continue
                }
                if (currentKeyUri != null && !trimmed.startsWith("#") && trimmed.isNotBlank()) {
                    val segMatch = SEGMENT_REGEX.find(trimmed)
                    val segNum = segMatch?.groupValues?.getOrNull(1)?.toLongOrNull()
                    if (segNum != null) {
                        val ivHex = segNum.toString(16).padStart(32, '0')
                        sb.append("""#EXT-X-KEY:METHOD=AES-128,URI="$currentKeyUri",IV=0x$ivHex""").append('\n')
                    }
                }
                sb.append(line).append('\n')
            }
            return sb.toString().trimEnd()
        }

        fun getImageHeaders(url: String? = null): Map<String, String> {
            val ua = cfUserAgent ?: USER_AGENT
            val host = url?.toHttpUrlOrNull()?.host ?: "animepahe.pw"
            val isPaheHost = host.contains("animepahe")
            val headers = mutableMapOf(
                "User-Agent" to ua,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            if (isPaheHost) {
                headers["Referer"] = "$MAIN_URL/"
                headers["sec-ch-ua-mobile"] = "?1"
                headers["sec-ch-ua-platform"] = "\"Android\""
                headers["Sec-Fetch-Dest"] = "image"
                headers["Sec-Fetch-Mode"] = "no-cors"
                headers["Sec-Fetch-Site"] = "same-site"
                val cookies = cfCookies ?: runCatching {
                    CookieManager.getInstance().getCookie("https://$host")
                        ?: CookieManager.getInstance().getCookie(MAIN_URL)
                }.getOrNull()
                if (!cookies.isNullOrBlank()) {
                    headers["Cookie"] = cookies
                }
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
            runCatching { cookieManager.flush() }
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
        fun solveCloudflare(appInstance: Requests, targetUrl: String = "$MAIN_URL/"): Boolean {
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

            val initialCookies = extractMergedCookies(targetUrl)
            val oldClearance = extractClearanceToken(initialCookies)
            Log.d("AnimePahe", "solveCloudflare start: oldClearance present=${oldClearance != null}")

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

                        val baseUa = userAgentString ?: cfUserAgent ?: USER_AGENT
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
                        cfUserAgent = sanitizedUa
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

                    fun checkClearanceAndDismissIfValid(url: String?) {
                        if (cookiesSaved || !dialog.isShowing) return
                        val currentCookies = extractMergedCookies(url ?: targetUrl)
                        val currentClearance = extractClearanceToken(currentCookies)
                        val currentTitle = webView.title ?: ""

                        val isChallenge = isChallengeTitle(currentTitle)
                        val isSiteLoaded = !isChallenge && currentTitle.contains("animepahe", ignoreCase = true)
                        val isNewClearance = oldClearance != null && currentClearance != null && currentClearance != oldClearance
                        val hasClearanceOnLoadedSite = currentClearance != null && !isChallenge

                        if (isSiteLoaded || isNewClearance || hasClearanceOnLoadedSite) {
                            Log.d("AnimePahe", "Verification confirmed (siteLoaded=$isSiteLoaded, newClearance=$isNewClearance, hasClearance=$hasClearanceOnLoadedSite, title='$currentTitle')! Dismissing dialog...")
                            saveCookiesAndDismiss(currentCookies)
                        }
                    }

                    val cookiePollRunnable = object : Runnable {
                        override fun run() {
                            if (cookiesSaved || !dialog.isShowing) return
                            pollElapsedMs += 800L
                            val currentTitle = webView.title ?: ""

                            Log.d("AnimePahe", "Poll [${pollElapsedMs}ms] title='$currentTitle'")

                            checkClearanceAndDismissIfValid(webView.url)

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
                            checkClearanceAndDismissIfValid(url)
                        }
                    }

                    root.addView(webView)
                    dialog.setContentView(root)

                    dialog.setOnDismissListener {
                        handler.removeCallbacks(cookiePollRunnable)
                        isDialogShowing.set(false)
                        if (!cookiesSaved) {
                            val finalCookies = extractMergedCookies(webView.url ?: targetUrl)
                            val finalClearance = extractClearanceToken(finalCookies)
                            val finalTitle = webView.title ?: ""
                            val isChallenge = isChallengeTitle(finalTitle)
                            val isSiteLoaded = !isChallenge && finalTitle.contains("animepahe", ignoreCase = true)
                            val isNewClearance = oldClearance != null && finalClearance != null && finalClearance != oldClearance
                            val hasClearanceOnLoadedSite = finalClearance != null && !isChallenge

                            if (isSiteLoaded || isNewClearance || hasClearanceOnLoadedSite) {
                                Log.d("AnimePahe", "Dismissed with valid session (siteLoaded=$isSiteLoaded, title='$finalTitle'). Saving cookies...")
                                saveCookiesAndDismiss(finalCookies)
                            } else {
                                Log.w("AnimePahe", "Dismissed without completing verification (title='$finalTitle')")
                                latch.countDown()
                            }
                        }
                    }

                    webView.loadUrl(targetUrl)
                    dialog.show()
                    handler.postDelayed(cookiePollRunnable, 1500L)

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

        val YOUTUBE_VIDEO_LINK =
            Regex("""(^(?:https?:)?(?://)?(?:www\.)?(?:youtu\.be/|youtube(?:-nocookie)?\.(?:[A-Za-z]{2,4}|[A-Za-z]{2,3}\.[A-Za-z]{2})/)(?:watch|embed/|vi?/)*(?:\?[\w=&]*vi?=)?[^#&?/]{11}.*$)""")
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
                val targetUrl = "$mainUrl/"
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
        MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", true)
    )

    // ── Data Models ──────────────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("images") val images: List<MetaImage>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListData(
        @JsonProperty("Media") val Media: AniListMedia? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
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
    private suspend fun fetchTopBanners(titles: List<String>): Map<Int, String> {
        if (titles.isEmpty()) return emptyMap()
        return runCatching {
            val queryParts = titles.mapIndexed { idx, title ->
                val clean = title
                    .replace(Regex("""\s*(?:Season|\(TV\)|Part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                    .trim()
                val escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
                "a$idx: Media(search: \"$escaped\", type: ANIME) { bannerImage coverImage { extraLarge } }"
            }.joinToString(" ")
            val resp = app.post(
                "https://graphql.anilist.co",
                json = mapOf("query" to "query { $queryParts }")
            ).text
            val jsonNode = AppUtils.mapper.readTree(resp)?.get("data")
            val map = mutableMapOf<Int, String>()
            titles.indices.forEach { idx ->
                val node = jsonNode?.get("a$idx")
                val banner = node?.get("bannerImage")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                val poster = node?.get("coverImage")?.get("extraLarge")?.asText()?.takeIf { it.isNotBlank() && it != "null" }
                val best = banner ?: poster
                if (!best.isNullOrBlank()) {
                    map[idx] = best
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val responseText = fetchWithCloudflare(url)
        val parsed = parseJson<AnimePaheLatestReleases>(responseText)

        val topBanners = if (page == 1 && parsed.data.isNotEmpty()) {
            fetchTopBanners(parsed.data.take(5).map { it.animeTitle })
        } else {
            emptyMap()
        }

        val items = parsed.data.mapIndexed { idx, item ->
            newAnimeSearchResponse(
                item.animeTitle,
                LoadData(item.animeSession, unixTime, item.animeTitle).toJson(),
                fix = false
            ) {
                val banner = topBanners[idx]
                val snap = fixImageUrl(item.snapshot)
                this.posterUrl = banner ?: snap
                this.posterHeaders = getImageHeaders(this.posterUrl)
                addDubStatus(DubStatus.Subbed, item.episode)
            }
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
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

            doc.select("a[href*='anilist.co'], a[href*='myanimelist.net']").forEach { aTag ->
                val href = aTag.attr("href")
                if (anilistId == null) {
                    anilistId = Regex("""anilist\.co/anime/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                }
                if (malId == null) {
                    malId = Regex("""myanimelist\.net/anime/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                }
            }

            var fanartUrl: String? = null
            var bannerUrl: String? = null
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
                        it.coverType.equals("Background", ignoreCase = true)
                    }?.url
                    bannerUrl = meta?.images?.firstOrNull {
                        it.coverType.equals("Banner", ignoreCase = true)
                    }?.url
                    highResPosterUrl = meta?.images?.firstOrNull { 
                        it.coverType.equals("Poster", ignoreCase = true) 
                    }?.url
                }
            }

            if (fanartUrl == null || highResPosterUrl == null) {
                runCatching {
                    val query = when {
                        anilistId != null -> "query{Media(id:$anilistId,type:ANIME){id bannerImage coverImage{extraLarge large}}}"
                        malId != null -> "query{Media(idMal:$malId,type:ANIME){id bannerImage coverImage{extraLarge large}}}"
                        else -> {
                            val cleanTitle = animeTitle
                                .replace(Regex("""\s*(?:Season|\(TV\)|Part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                                .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
                                .trim()
                            val escapedTitle = cleanTitle.replace("\\", "\\\\").replace("\"", "\\\"")
                            "query{Media(search:\"$escapedTitle\",type:ANIME){id bannerImage coverImage{extraLarge large}}}"
                        }
                    }
                    val resp = app.post(
                        "https://graphql.anilist.co",
                        json = mapOf("query" to query)
                    ).parsedSafe<AniListResponse>()
                    val media = resp?.data?.Media
                    if (anilistId == null && media?.id != null) {
                        anilistId = media.id
                    }
                    if (fanartUrl == null && !media?.bannerImage.isNullOrBlank()) {
                        fanartUrl = media?.bannerImage
                    }
                    if (highResPosterUrl == null) {
                        highResPosterUrl = media?.coverImage?.extraLarge ?: media?.coverImage?.large
                    }
                }
            }

            val genres = doc.select(".anime-genre > ul a").map { it.text() }

            val bestHeroBanner = fanartUrl ?: bannerUrl ?: highResPosterUrl ?: fixImageUrl(poster)
            val bestPoster = highResPosterUrl ?: fixImageUrl(poster)

            newAnimeLoadResponse(animeTitle, url, tvType) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = bestPoster
                this.backgroundPosterUrl = bestHeroBanner
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

    // ── Kwik Stream Unpacker ──────────────────────────────────────────
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

    // ── Video Interceptor for HLS AES-128 Stream Decryption ───────────
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (extractorLink.type != ExtractorLinkType.M3U8 && !extractorLink.url.contains(".m3u8")) {
            return null
        }
        return Interceptor { chain ->
            val request = chain.request()
            val reqBuilder = request.newBuilder()
            if (request.header("Referer").isNullOrBlank()) {
                reqBuilder.header("Referer", "https://kwik.cx/")
            }
            val response = chain.proceed(reqBuilder.build())
            if (!request.url.encodedPath.endsWith(".m3u8")) {
                return@Interceptor response
            }
            val body = response.body ?: return@Interceptor response
            val contentType = body.contentType()
            val bodyStr = body.string()
            if (bodyStr.contains("#EXT-X-KEY:METHOD=AES-128") && !bodyStr.contains(",IV=0x")) {
                val fixed = fixHlsAesSequenceIvs(bodyStr)
                response.newBuilder()
                    .body(fixed.toByteArray(Charsets.UTF_8).toResponseBody(contentType))
                    .build()
            } else {
                response.newBuilder()
                    .body(bodyStr.toByteArray(Charsets.UTF_8).toResponseBody(contentType))
                    .build()
            }
        }
    }
}

