package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
                    newExtractorLink(
                        source = name,
                        name = "$name - ${if (isFsl2) "FSLv2" else "FSL"} [Fast R2 Stream ⚡]",
                        url = href,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }

        // Pixeldrain mirror resolution (supports HTTP Range seeking)
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
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        doc.select("a[href*='vcloud.'], a[href*='fast-dl.'], a[href*='fastdl.'], a[href*='zee-dl.'], a[href*='zeedl.'], a[href*='gofile.io'], a[href*='filebee.'], a[href*='filepress.'], a[href*='one.vegadrive']").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http")) {
                loadExtractor(href, url, subtitleCallback, callback)
            }
        }
    }
}

class NexdriveYouExtractor : NexdriveExtractor() {
    override val mainUrl = "https://nexdrive.you"
}

class NexdriveStoreExtractor : NexdriveExtractor() {
    override val mainUrl = "https://nexdrive.store"
}

open class FastDLExtractor : ExtractorApi() {
    override val name = "FastDL"
    override val mainUrl = "https://fast-dl.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        // 1. Direct reurl script detection (exclude non-seekable Google download links)
        val reurlMatch = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!reurlMatch.isNullOrBlank() && !reurlMatch.contains("video-downloads.googleusercontent.com")) {
            val cdnMatch = Regex("""link=(https?://[^"'\s&]+)""").find(reurlMatch)
            val streamTarget = cdnMatch?.groupValues?.get(1) ?: reurlMatch
            if (streamTarget.startsWith("http") && !streamTarget.contains("video-downloads.googleusercontent.com")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Instant Stream [⚡]",
                        url = streamTarget,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }
        }

        // 2. Direct download button extraction (exclude non-seekable Google download links)
        val btnMatch = Regex("""href=["'](dl\.php\?code=[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!btnMatch.isNullOrBlank()) {
            val cleanBtn = btnMatch.replace("&amp;", "&")
            val prepUrl = "$mainUrl/$cleanBtn&prepare=1"
            runCatching {
                val jsonRes = app.get(
                    prepUrl,
                    headers = mapOf(
                        "User-Agent" to VCloudExtractor.USER_AGENT,
                        "Referer" to url,
                        "Accept" to "application/json"
                    ),
                    timeout = 6
                ).parsedSafe<Map<String, Any?>>()
                val target = jsonRes?.get("url") as? String
                if (!target.isNullOrBlank() && target.startsWith("http") && !target.contains("video-downloads.googleusercontent.com")) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - Direct Stream [⚡]",
                            url = target,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        }
    }
}

class ZeeDlExtractor : FastDLExtractor() {
    override val name = "Zee-DL"
    override val mainUrl = "https://zee-dl.lol"
}

class FastDlBeerExtractor : FastDLExtractor() {
    override val mainUrl = "https://fastdl.beer"
}

class FastDlLolExtractor : FastDLExtractor() {
    override val mainUrl = "https://fastdl.lol"
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
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text
        }.getOrNull() ?: return

        val modproMatch = Regex("""https?://modpro\.[a-z]+/drive/[0-9a-zA-Z]+""").find(html)?.value
        if (!modproMatch.isNullOrBlank()) {
            val modHtml = runCatching {
                app.get(modproMatch, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to url)).text
            }.getOrNull() ?: return

            val gofileMatch = Regex("""https?://gofile\.io/d/[0-9a-zA-Z]+""").find(modHtml)?.value
            if (!gofileMatch.isNullOrBlank()) {
                loadExtractor(gofileMatch, subtitleCallback, callback)
            }
        }
    }
}

open class HubdriveExtractor : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.bar"
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

        val hubcloudMatch = Regex("""href=["'](https?://hubcloud\.[a-z]+/drive/[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!hubcloudMatch.isNullOrBlank()) {
            loadExtractor(hubcloudMatch, subtitleCallback, callback)
        }
    }
}

open class HubcloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val driveDoc = runCatching {
            Jsoup.parse(app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT)).text)
        }.getOrNull() ?: return

        val phpBtn = driveDoc.selectFirst("a[href*='hubcloud.php']")?.attr("href") ?: return
        val phpDoc = runCatching {
            Jsoup.parse(
                app.get(
                    phpBtn,
                    headers = mapOf(
                        "User-Agent" to VCloudExtractor.USER_AGENT,
                        "Referer" to url
                    )
                ).text
            )
        }.getOrNull() ?: return

        phpDoc.select("a.btn, a[href*='download'], div.card-body a").forEach { btn ->
            val href = btn.attr("href").trim()
            when {
                href.contains("cloudflarestorage.com") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - [Fast Server : Cloudflare R2 ⚡]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                href.contains("pixel.hubcloud") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - [10Gbps PixelServer ⚡]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = phpBtn
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                href.contains("pixeldrain") -> {
                    runCatching {
                        loadExtractor(href, subtitleCallback, callback)
                    }
                }
                href.contains("fuckingfast.net") -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - [BuzzServer Fast]",
                            url = href,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = ""
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
                href.contains("hbplay.pages.dev") && href.contains("?u=") -> {
                    val encoded = href.substringAfter("?u=").substringBefore("&")
                    val decoded = runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - [WebPlayer Stream ⚡]",
                                url = decoded,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = ""
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            }
        }
    }
}

open class GroundbanksExtractor : ExtractorApi() {
    override val name = "Groundbanks"
    override val mainUrl = "https://groundbanks.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://www.bollyzone.to/"))).text
        }.getOrNull() ?: return

        val iframe = Regex("""<iframe[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!iframe.isNullOrBlank()) {
            loadExtractor(iframe, url, subtitleCallback, callback)
        }
    }
}

open class TvlogyExtractor : ExtractorApi() {
    override val name = "Tvlogy"
    override val mainUrl = "https://flow.tvlogy.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://groundbanks.net/"))).text
        }.getOrNull() ?: return

        // 1. Plain Video.js source check
        var streamUrl = Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)

        // 2. JuicyCodes packer unpack
        if (streamUrl.isNullOrBlank()) {
            val juicyMatch = Regex("""JuicyCodes\.Run\(([\s\S]*?)\);""").find(html)?.groupValues?.get(1)
            if (!juicyMatch.isNullOrBlank()) {
                val b64Chunks = Regex(""""([^"]+)"""").findAll(juicyMatch).map { it.groupValues[1] }.joinToString("")
                val packed = runCatching {
                    String(Base64.decode(b64Chunks, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
                if (!packed.isNullOrBlank()) {
                    val unpacked = getAndUnpack(packed)
                    streamUrl = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(unpacked)?.value
                }
            }
        }

        if (!streamUrl.isNullOrBlank()) {
            val streamHeaders = mapOf(
                "User-Agent" to VCloudExtractor.USER_AGENT,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - HLS [Daily Stream ⚡]",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = streamHeaders
                    this.quality = Qualities.P720.value
                }
            )
        }
    }
}

open class TvarticlesExtractor : ExtractorApi() {
    override val name = "Tvarticles"
    override val mainUrl = "https://tvarticles.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://www.desi-serials.to/"))).text
        }.getOrNull() ?: return

        val iframe = Regex("""<iframe[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!iframe.isNullOrBlank()) {
            loadExtractor(iframe, url, subtitleCallback, callback)
        }
    }
}

open class CineverseExtractor : ExtractorApi() {
    override val name = "Cineverse"
    override val mainUrl = "https://rozgarlelo.modiplay.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://m1.multimovies.courses/"))).text
        }.getOrNull() ?: return

        val iframeSrc = Regex("""<iframe[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        val targetUrl = if (!iframeSrc.isNullOrBlank()) {
            if (iframeSrc.startsWith("http")) iframeSrc else "https://rozgarlelo.modiplay.xyz$iframeSrc"
        } else url

        val proxyHtml = if (targetUrl != url) {
            runCatching {
                app.get(targetUrl, headers = mapOf("Referer" to url)).text
            }.getOrNull() ?: html
        } else html

        val embedUrl = Regex("""EMBED_URL\s*=\s*['"]([^'"]+)['"]""").find(proxyHtml)?.groupValues?.get(1)
            ?: Regex("""https?://vidara\.[a-z]+/e/[a-zA-Z0-9]+""").find(proxyHtml)?.value

        if (!embedUrl.isNullOrBlank()) {
            loadExtractor(embedUrl, targetUrl, subtitleCallback, callback)
        }
    }
}

open class FilmuExtractor : ExtractorApi() {
    override val name = "Filmu"
    override val mainUrl = "https://embed.filmu.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("Referer" to (referer ?: "https://m1.multimovies.courses/"))).text
        }.getOrNull() ?: return

        val m3u8Url = Regex("""https?://[^\s"'<>\)]+\.m3u8[^\s"'<>\)]*""").find(html)?.value
        if (!m3u8Url.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - HLS [MultiMovies ⚡]",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://embed.filmu.in/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

// ─── FuckingFast (ffast_res) Extractor ───────────────────────────────────────

open class FuckingfastExtractor : ExtractorApi() {
    override val name = "FuckingFast"
    override val mainUrl = "https://fuckingfast.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        // Direct download URL embedded in page or button href
        val directMatch = Regex("""href=["'](https?://[^"']+(?:\.mp4|\.mkv|\.m3u8)[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""window\.location\.href\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
        if (!directMatch.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - Fast CDN [⚡]",
                    url = directMatch,
                    type = if (directMatch.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

// ─── Katdrive Extractor ───────────────────────────────────────────────────────

open class KatdriveExtractor : ExtractorApi() {
    override val name = "Katdrive"
    override val mainUrl = "https://katdrive.click"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""/(?:file|stream)/([0-9a-zA-Z]+)""").find(url)?.groupValues?.get(1) ?: return
        val playUrl = "https://katdrive.click/play.php?id=$fileId"
        val playHtml = runCatching {
            app.get(playUrl, headers = mapOf("Referer" to "https://katdrive.click/stream/$fileId")).text
        }.getOrNull() ?: return

        val m3u8Url = Regex("""["']?(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(playHtml)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>\)]+\.m3u8[^\s"'<>\)]*""").find(playHtml)?.value

        if (!m3u8Url.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - HLS [KatMovieHD ⚡]",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://katdrive.click/"
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}

// ─── Greenmotors Extractor ───────────────────────────────────────────────────

data class GreenmotorsPayload(
    val o: String? = null,
    val l: String? = null,
    val w: Int? = null
)

open class GreenmotorsExtractor : ExtractorApi() {
    override val name = "Greenmotors"
    override val mainUrl = "https://greenmotors.cc"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://new8.hdhub4u.cl/"))).text
        }.getOrNull() ?: return

        // Extract inline s('o', '<ENCRYPTED_STRING>', 180*1000);
        val match = Regex("""s\(['"]o['"],\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1) ?: return
        val s1 = runCatching { String(Base64.decode(match, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val s2 = runCatching { String(Base64.decode(s1, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val s3 = rot13(s2)
        val s4 = runCatching { String(Base64.decode(s3, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return
        val json = runCatching { parseJson<GreenmotorsPayload>(s4) }.getOrNull() ?: return
        val encO = json.o ?: return
        val targetUrl = runCatching { String(Base64.decode(encO, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return

        if (targetUrl.startsWith("http")) {
            if (targetUrl.endsWith(".mkv") || targetUrl.endsWith(".mp4") || targetUrl.contains("workers.dev")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Direct Stream [⚡]",
                        url = targetUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = Qualities.P720.value
                    }
                )
            } else {
                loadExtractor(targetUrl, url, subtitleCallback, callback)
            }
        }
    }

    private fun rot13(str: String): String {
        return str.map { c ->
            when (c) {
                in 'a'..'z' -> if (c <= 'm') c + 13 else c - 13
                in 'A'..'Z' -> if (c <= 'M') c + 13 else c - 13
                else -> c
            }
        }.joinToString("")
    }
}

class GreenmotorsClubExtractor : GreenmotorsExtractor() {
    override val mainUrl = "https://greenmotors.club"
}

// ─── HBLinks Extractor ───────────────────────────────────────────────────────

open class HblinksExtractor : ExtractorApi() {
    override val name = "HBLinks"
    override val mainUrl = "https://hblinks.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: mainUrl))).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !href.contains("hblinks.lol/category") && !href.contains("wp-admin")) {
                when {
                    href.contains("hubcloud.") || href.contains("hubdrive.") || href.contains("hubcdn.") ||
                    href.contains("pixeldrain.") || href.contains("fastdl.") || href.contains("fuckingfast.") ||
                    href.contains("gdtot.") || href.contains(".workers.dev") -> {
                        loadExtractor(href, url, subtitleCallback, callback)
                    }
                }
            }
        }
    }
}

// ─── HubCDN Extractor ────────────────────────────────────────────────────────

open class HubcdnExtractor : ExtractorApi() {
    override val name = "HubCDN"
    override val mainUrl = "https://hubcdn.club"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to VCloudExtractor.USER_AGENT, "Referer" to (referer ?: "https://hblinks.lol/"))).text
        }.getOrNull() ?: return

        val reurlParam = Regex("""[?&]r=([A-Za-z0-9+/=]+)""").find(html)?.groupValues?.get(1)
        val decodedParam = if (!reurlParam.isNullOrBlank()) {
            runCatching { String(Base64.decode(reurlParam, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
        } else null

        val streamUrl = (decodedParam ?: html).let { text ->
            Regex("""[?&]link=(https?://[^"'\s&]+)""").find(text)?.groupValues?.get(1)
                ?: Regex("""https?://[a-zA-Z0-9.-]+\.r2\.dev/[a-zA-Z0-9]+""").find(text)?.value
                ?: Regex("""https?://[a-zA-Z0-9.-]+\.cloudflarestorage\.com/[^\s"'<>]+""").find(text)?.value
        }

        if (!streamUrl.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - Direct [R2 Dev ⚡]",
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}


