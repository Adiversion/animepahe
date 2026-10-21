package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

open class VCloudExtractor : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.beer"
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
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val tokenUrl = runCatching {
            val match = Regex("""atob\(atob\(['"]([A-Za-z0-9+/=]+)['"]\)\)""").find(html)?.groupValues?.get(1)
            if (match != null) {
                val step1 = String(Base64.decode(match, Base64.DEFAULT), Charsets.UTF_8)
                String(Base64.decode(step1, Base64.DEFAULT), Charsets.UTF_8)
            } else null
        }.getOrNull()

        val finalDoc = if (!tokenUrl.isNullOrBlank()) {
            runCatching {
                val tHtml = app.get(
                    tokenUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url),
                    timeout = 8
                ).text
                Jsoup.parse(tHtml)
            }.getOrNull() ?: Jsoup.parse(html)
        } else {
            Jsoup.parse(html)
        }

        val fslLinks = finalDoc.select("a[href*='r2.dev'], a[href*='r2.cloudflarestorage.com'], a:contains(FSL)")
        fslLinks.forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                val isFsl2 = a.text().contains("FSLv2", ignoreCase = true)
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - ${if (isFsl2) "FSLv2" else "FSL"} [Fast R2 Stream ⚡]",
                        url = href,
                        referer = "",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        val cdn10g = finalDoc.select("a:contains(10Gbps), a[href*='hubcloud.ist']")
        cdn10g.forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                runCatching {
                    val resp = app.get(
                        href,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url),
                        allowRedirects = false,
                        timeout = 6
                    )
                    val loc = resp.headers["Location"] ?: resp.headers["location"]
                    val targetUrl = loc ?: href
                    val directCdn = Regex("""link=([^&]+)""").find(targetUrl)?.groupValues?.get(1) ?: targetUrl
                    if (directCdn.startsWith("http")) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - 10Gbps [Google CDN]",
                                url = directCdn,
                                referer = "",
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            }
        }

        finalDoc.select("a[href*='pixeldrain.dev'], a[href*='pixeldrain.com']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                loadExtractor(href, subtitleCallback, callback)
            }
        }
    }
}

open class NexdriveExtractor : ExtractorApi() {
    override val name = "Nexdrive"
    override val mainUrl = "https://nexdrive.beer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://rogmovies.one/"))).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        doc.select("a[href*='vcloud.'], a[href*='fastdl.'], a[href*='gofile.io'], a[href*='filebee.'], a[href*='one.vegadrive']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                runCatching {
                    loadExtractor(href, subtitleCallback, callback)
                }
            }
        }
    }
}

open class FastDLExtractor : ExtractorApi() {
    override val name = "FastDL"
    override val mainUrl = "https://fastdl.beer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text
        }.getOrNull() ?: return

        val cdnMatch = Regex("""https://video-downloads\.googleusercontent\.com/[A-Za-z0-9_-]+""").find(html)?.value
        if (cdnMatch != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name - 10Gbps [Google CDN]",
                    url = cdnMatch,
                    referer = "",
                    quality = Qualities.P1080.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
    }
}

open class DriveSeedExtractor : ExtractorApi() {
    override val name = "DriveSeed"
    override val mainUrl = "https://driveseed.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://moviesmod.vip/"))).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        doc.select("a[href*='r2.dev'], a:contains(Cloud Download)").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - Cloud [R2 Stream ⚡]",
                        url = href,
                        referer = "",
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        }

        doc.select("a[href*='video-gen.xyz'], a:contains(Instant Download)").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                runCatching {
                    val resp = app.get(
                        href,
                        headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to url),
                        allowRedirects = true,
                        timeout = 6
                    )
                    val cdnMatch = Regex("""https://video-downloads\.googleusercontent\.com/[A-Za-z0-9_-]+""").find(resp.url)
                        ?: Regex("""https://video-downloads\.googleusercontent\.com/[A-Za-z0-9_-]+""").find(resp.text)
                    if (cdnMatch != null) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name - Instant [Google CDN]",
                                url = cdnMatch.value,
                                referer = "",
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                }
            }
        }
    }
}
