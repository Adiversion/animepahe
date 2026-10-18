package com.lagradost

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

// ── Native Cloudflare Verification BottomSheetDialogFragment ─────────────────────────
class CloudflareWebViewDialog(
    private val targetUrl: String = AnimePaheProvider.MAIN_URL,
    private val onFinished: (Boolean) -> Unit = {}
) : BottomSheetDialogFragment() {

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cookiesSaved = false
    private val handler = Handler(Looper.getMainLooper())
    private var pollElapsedMs = 0L

    companion object {
        const val POLL_INTERVAL_MS = 800L
        const val POLL_TIMEOUT_MS = 60_000L

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
    }

    private val cursorDrawable = object : Drawable() {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 32f)
            lineTo(9f, 25f)
            lineTo(16f, 40f)
            lineTo(22f, 37f)
            lineTo(15f, 22f)
            lineTo(26f, 22f)
            close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return
            pollElapsedMs += POLL_INTERVAL_MS
            val currentCookies = extractMergedCookies(webView?.url ?: targetUrl)
            val currentTitle = webView?.title ?: ""

            Log.d("AnimePahe", "Poll [${pollElapsedMs}ms] title='$currentTitle'")

            // Auto-trigger Turnstile checkmark click
            tryAutoCheck()

            if (hasClearance(currentCookies) && !isChallengeTitle(currentTitle)) {
                Log.d("AnimePahe", "Clearance confirmed! Saving cookies and dismissing dialog...")
                saveCookiesAndDismiss(currentCookies)
                return
            }

            if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                Log.w("AnimePahe", "Turnstile verification timed out after 60s")
                notifyFinished(false)
                dismissAllowingStateLoss()
                return
            }

            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun extractMergedCookies(currentUrl: String?): String {
        val cookieManager = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        val domains = listOfNotNull(
            currentUrl,
            AnimePaheProvider.MAIN_URL,
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

    private fun tryAutoCheck() {
        val script = """
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
        runCatching { webView?.evaluateJavascript(script, null) }
    }

    private fun saveCookiesAndDismiss(cookies: String) {
        if (cookiesSaved) return
        cookiesSaved = true

        handler.removeCallbacks(cookiePollRunnable)
        CookieManager.getInstance().flush()

        AnimePaheProvider.cfCookies = cookies
        AnimePaheProvider.cfUserAgent = webView?.settings?.userAgentString

        AnimePaheProvider.syncCookiesToApp()

        statusText?.text = "✅ Verification successful!"
        statusText?.setTextColor(Color.parseColor("#4CAF50"))
        progressBar?.visibility = View.GONE

        notifyFinished(true)

        handler.postDelayed({
            runCatching {
                if (isAdded) {
                    dismissAllowingStateLoss()
                }
            }
        }, 500L)
    }

    private fun notifyFinished(success: Boolean) {
        runCatching { onFinished(success) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = Resources.getSystem().displayMetrics.heightPixels
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            sheet.setBackgroundColor(Color.parseColor("#121218"))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121218"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 0)
        }

        // Top Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1E1E2A"))
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleTv = TextView(context).apply {
            text = "AnimePahe Cloudflare Verification"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(context).apply {
            text = "✕"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 18f
            setOnClickListener { dismissAllowingStateLoss() }
        }

        header.addView(titleTv)
        header.addView(closeBtn)
        root.addView(header)

        // Subtitle / status
        statusText = TextView(context).apply {
            text = "Please complete the verification checkmark below. The dialog will close automatically once done."
            setTextColor(Color.parseColor("#A0A0B0"))
            textSize = 12f
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }
        root.addView(statusText)

        // Progress bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * density).toInt()
            )
        }
        root.addView(progressBar)

        // FrameLayout for WebView and TV Remote Cursor
        val webContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val wv = buildWebView(context)
        this.webView = wv
        webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // TV Remote virtual cursor pointer overlay
        val cursorView = ImageView(context).apply {
            setImageDrawable(cursorDrawable)
            elevation = 10f * density
            layoutParams = FrameLayout.LayoutParams((28 * density).toInt(), (40 * density).toInt())
            x = 100f * density
            y = 100f * density
        }
        webContainer.addView(cursorView)

        root.addView(webContainer)

        setupTvRemoteNavigation(cursorView, wv, density)

        wv.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        return root
    }

    private fun setupTvRemoteNavigation(cursorView: ImageView, wv: WebView, density: Float) {
        val step = 30f * density

        dialog?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                var curX = cursorView.x
                var curY = cursorView.y
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        curY = (curY - step).coerceAtLeast(0f)
                        cursorView.y = curY
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        curY = (curY + step).coerceAtMost((wv.height - cursorView.height).toFloat())
                        cursorView.y = curY
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        curX = (curX - step).coerceAtLeast(0f)
                        cursorView.x = curX
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        curX = (curX + step).coerceAtMost((wv.width - cursorView.width).toFloat())
                        cursorView.x = curX
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val clickX = curX + (2 * density)
                        val clickY = curY + (2 * density)
                        val downTime = SystemClock.uptimeMillis()
                        wv.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, clickX, clickY, 0))
                        wv.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, clickX, clickY, 0))
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context): WebView {
        val wv = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.settings.apply {
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

            val baseUa = userAgentString ?: AnimePaheProvider.USER_AGENT
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

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar?.progress = newProgress
                if (newProgress > 50) {
                    tryAutoCheck()
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar?.visibility = View.GONE
                tryAutoCheck()
            }
        }

        return wv
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        handler.removeCallbacks(cookiePollRunnable)
        if (!cookiesSaved) {
            val finalCookies = extractMergedCookies(webView?.url ?: targetUrl)
            val finalTitle = webView?.title ?: ""
            if (hasClearance(finalCookies) && !isChallengeTitle(finalTitle)) {
                saveCookiesAndDismiss(finalCookies)
            } else {
                notifyFinished(false)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(cookiePollRunnable)
        webView?.destroy()
        webView = null
    }
}

// ── Main AnimePahe Provider ──────────────────────────────────────────────────────────
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

        private val cfMutex = Mutex()
        private var lastCfDialogAtMs: Long = 0L

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

        fun getImageHeaders(base: String = MAIN_URL): Map<String, String> {
            val ua = cfUserAgent ?: USER_AGENT
            val headers = mutableMapOf(
                "User-Agent" to ua,
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

        fun fixImageUrl(url: String?, base: String = MAIN_URL): String? {
            if (url.isNullOrBlank()) return null
            return when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$base$url"
                else -> url
            }
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

        fun syncCookiesToApp(base: String = MAIN_URL) {
            val httpUrl = base.toHttpUrlOrNull() ?: return
            val cookies = cfCookies ?: runCatching {
                CookieManager.getInstance().getCookie(base)
            }.getOrNull() ?: return
            val list = ArrayList<Cookie>()
            cookies.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.isNotBlank()) {
                    Cookie.parse(httpUrl, "$trimmed; Path=/")?.let { list.add(it) }
                }
            }
            if (list.isNotEmpty()) {
                runCatching {
                    app.baseClient.cookieJar.saveFromResponse(httpUrl, list)
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

        suspend fun showCFBypassDialogAndWait(targetUrl: String): Boolean = withContext(Dispatchers.Main) {
            val activity = (CommonActivity.activity ?: getActivity()) as? AppCompatActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                Log.e("AnimePahe", "No active AppCompatActivity found for Cloudflare dialog")
                return@withContext false
            }

            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                fun safeResume(success: Boolean) {
                    if (resumed.compareAndSet(false, true)) {
                        if (continuation.isActive) {
                            continuation.resume(success)
                        }
                    }
                }

                val dialog = CloudflareWebViewDialog(targetUrl) { success ->
                    safeResume(success)
                }

                continuation.invokeOnCancellation {
                    runCatching {
                        if (dialog.isAdded) {
                            dialog.dismissAllowingStateLoss()
                        }
                    }
                }

                try {
                    val fm = activity.supportFragmentManager
                    val existing = fm.findFragmentByTag("cf_bypass_auto")
                    val tx = fm.beginTransaction()
                    if (existing != null) {
                        tx.remove(existing)
                    }
                    tx.add(dialog, "cf_bypass_auto")
                    tx.commitAllowingStateLoss()
                } catch (e: Throwable) {
                    Log.e("AnimePahe", "Failed to show CF dialog: ${e.message}")
                    safeResume(false)
                }
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
        Log.d("AnimePahe", "fetchWithCloudflare: GET $url")
        // 1. Fast path: check with current cookies
        val initial = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
        if (initial != null && !isBlocked(initial)) {
            return initial.text
        }

        Log.w("AnimePahe", "Request blocked (code=${initial?.code}). Acquiring lock for verification...")

        // 2. Thread synchronization: only ONE coroutine shows the verification dialog
        return cfMutex.withLock {
            // Re-check inside lock: previous thread might have already solved Cloudflare
            val recheck = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
            if (recheck != null && !isBlocked(recheck)) {
                Log.d("AnimePahe", "Verification already resolved by previous task (200 OK)")
                return@withLock recheck.text
            }

            // Throttling: avoid rapid dialog restarts within 5 seconds
            val now = System.currentTimeMillis()
            if (now - lastCfDialogAtMs < 5000L) {
                Log.d("AnimePahe", "Dialog recently shown, throttling retry...")
                delay(1500L)
                val throttledRetry = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
                if (throttledRetry != null && !isBlocked(throttledRetry)) {
                    return@withLock throttledRetry.text
                }
            }
            lastCfDialogAtMs = now

            CommonActivity.showToast("Opening AnimePahe Verification...")
            val targetUrl = "$mainUrl/api?m=airing&page=1"
            val solved = showCFBypassDialogAndWait(targetUrl)
            Log.d("AnimePahe", "CF bypass dialog completed with result: $solved")

            // Re-attempt request with newly saved cookies
            val retry = runCatching { app.get(url, headers = getHeaders()) }.getOrNull()
            if (retry != null && !isBlocked(retry)) {
                CommonActivity.showToast("Verification successful!")
                return@withLock retry.text
            }

            throw ErrorLoadingException("Cloudflare challenge encountered. Please complete the verification checkmark.")
        }
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
                this.posterUrl = fixImageUrl(item.snapshot)
                this.posterHeaders = getImageHeaders()
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
                this.posterUrl = fixImageUrl(item.poster)
                this.posterHeaders = getImageHeaders()
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

            val genres = doc.select(".anime-genre > ul a").map { it.text() }

            newAnimeLoadResponse(animeTitle, url, tvType) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = fixImageUrl(poster)
                this.backgroundPosterUrl = fixImageUrl(episodes.firstOrNull()?.posterUrl ?: poster)
                this.posterHeaders = getImageHeaders()
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
