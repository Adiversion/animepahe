package com.lagradost

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

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
        val cleanUrl = url.trim().replace("bollydrive.mom", "bollydrive.buzz")
        val doc = runCatching {
            val text = app.get(cleanUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
            Jsoup.parse(text)
        }.getOrNull() ?: return

        val pageTitle = doc.title().trim()
        val qualityMatch = Regex("""\b(480|720|1080|2160)\b""").find(pageTitle)?.groupValues?.get(1)
        val qualityInt = when (qualityMatch) {
            "2160" -> Qualities.P2160.value
            "1080" -> Qualities.P1080.value
            "720" -> Qualities.P720.value
            "480" -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
        val qualityBadge = qualityMatch?.let { "${it}p" } ?: "1080p"

        // 1. Resolve 10Gbps Google Photos CDN stream via handle10GbpsDownload
        val handleMatch = Regex("""handle10GbpsDownload\(this,\s*'([^']+)',\s*'([^']*)',\s*'([^']*)'\)""").find(doc.html())
        if (handleMatch != null) {
            val shareId = handleMatch.groupValues[1]
            var encodedUrl = handleMatch.groupValues[3].trim()

            // If encodedUrl is empty, query photos-stream-api.php?id={shareId}
            if (encodedUrl.isBlank() && shareId.isNotBlank()) {
                val prefetchUrl = "$mainUrl/photos-stream-api.php?id=$shareId"
                val json = runCatching {
                    app.get(prefetchUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 6).parsedSafe<Map<String, Any?>>()
                }.getOrNull()
                val photosUrl = json?.get("photos_url") as? String
                if (!photosUrl.isNullOrBlank()) {
                    encodedUrl = Base64.encodeToString(photosUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                }
            }

            if (encodedUrl.isNotBlank()) {
                resolveGooglePhotosStream(cleanUrl, encodedUrl, qualityBadge, qualityInt, callback)
            }
        }

        // 2. Discover and resolve mirror download buttons (XCloud, FileBee, etc.)
        doc.select("a.btn-dl, a.mirror-link, a[href*='xcloud'], a[href*='filebee'], a[href*='filepress']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !href.contains("bollydrive.buzz") && !href.contains("bollydrive.mom") && !href.contains("gdflix")) {
                runCatching {
                    loadExtractor(href, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun resolveGooglePhotosStream(
        refererUrl: String,
        encodedUrl: String,
        qualityBadge: String,
        qualityInt: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val processUrl = "$mainUrl/process_download.php?data=" + URLEncoder.encode(encodedUrl, "UTF-8")

            // Step 1: Follow 302 redirect with allowRedirects=false to get Google Photos lh3 URL without downloading body
            val step1 = app.get(
                processUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to refererUrl
                ),
                allowRedirects = false,
                timeout = 10
            )

            val lh3Url = step1.headers["Location"] ?: step1.headers["location"]
            if (lh3Url.isNullOrBlank()) return@runCatching

            // Step 2: Use HEAD to get the final signed video-downloads.googleusercontent.com stream URL
            val step2 = runCatching {
                app.head(
                    lh3Url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    allowRedirects = false,
                    timeout = 10
                )
            }.getOrNull()

            val finalStreamUrl = step2?.headers?.get("Location") ?: step2?.headers?.get("location") ?: lh3Url

            val link = newExtractorLink(
                source = name,
                name = "$name - $qualityBadge [10Gbps Google CDN]",
                url = finalStreamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = ""
                this.quality = qualityInt
            }
            callback(link)
        }.onFailure { e ->
            Log.e("Bollydrive", "Failed to resolve 10Gbps stream: ${e.message}", e)
        }
    }
}

class BollydriveMomExtractor : BollydriveExtractor() {
    override val mainUrl = "https://bollydrive.mom"
}
