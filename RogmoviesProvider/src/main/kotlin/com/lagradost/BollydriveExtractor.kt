package com.lagradost

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ExtractorLink
import org.jsoup.Jsoup

open class BollydriveExtractor : ExtractorApi() {
    override val name = "Bollydrive"
    override val mainUrl = "https://bollydrive.buzz"
    override val requiresReferer = false

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val doc = runCatching {
            val text = app.get(cleanUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            Jsoup.parse(text)
        }.getOrNull() ?: return

        // 1. Check for 10Gbps Google Photos handle10GbpsDownload button
        val handleMatch = Regex("""handle10GbpsDownload\(this,\s*'([^']+)',\s*'([^']*)',\s*'([^']*)'\)""").find(doc.html())
        if (handleMatch != null) {
            val shareId = handleMatch.groupValues[1]
            val encodedUrl = handleMatch.groupValues[3]

            var photosUrl: String? = null
            if (encodedUrl.isNotBlank()) {
                photosUrl = runCatching {
                    String(Base64.decode(encodedUrl, Base64.DEFAULT), Charsets.UTF_8).trim()
                }.getOrNull()
            }

            // Fallback: poll prefetch API if encodedUrl was empty
            if (photosUrl.isNullOrBlank() && shareId.isNotBlank()) {
                val prefetchUrl = "$mainUrl/photos-stream-api.php?action=prefetch&id=$shareId"
                val json = runCatching {
                    app.get(prefetchUrl, headers = mapOf("User-Agent" to USER_AGENT)).parsedSafe<Map<String, Any?>>()
                }.getOrNull()
                photosUrl = json?.get("photos_url") as? String
            }

            if (!photosUrl.isNullOrBlank()) {
                Log.d("Bollydrive", "Delegating Google Photos URL: $photosUrl")
                loadExtractor(photosUrl, subtitleCallback, callback)
            }
        }

        // 2. Discover and resolve mirror download buttons (FilePress, GDFlix, XCloud)
        doc.select("a.btn-dl, a.mirror-link, a[href*='filebee'], a[href*='filepress'], a[href*='gdflix'], a[href*='xcloud']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !href.contains("bollydrive.buzz") && !href.contains("bollydrive.mom")) {
                Log.d("Bollydrive", "Delegating mirror URL: $href")
                loadExtractor(href, subtitleCallback, callback)
            }
        }
    }
}
