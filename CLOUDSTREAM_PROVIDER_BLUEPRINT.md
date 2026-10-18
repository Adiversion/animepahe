# CloudStream Provider Architecture & Rapid Engineering Blueprint

A production-grade, battle-tested guide for building high-performance CloudStream streaming providers from scratch or converting closed-source plugins into clean open-source repositories in **under 45–60 minutes**.

---

## 1. Fast-Track Timeline (< 60 Minutes)

```
Phase 0: Pre-Flight Site Audit (10m) ────> Phase 1: Project Setup (5m)
                                                   │
                                                   ▼
Phase 3: Video Decryption & Streams (10m) <── Phase 2: Core Methods Implementation (25m)
                 │
                 ▼
Phase 4: CI/CD Build & Verification (5m) ───> Production Ready (.cs3)
```

---

## 2. Phase 0: Pre-Flight Site Audit (10 Minutes)

Before writing any Kotlin code, inspect the target site in desktop Chrome DevTools:

| Inspection Area | What to Look For | Engineering Decision |
| :--- | :--- | :--- |
| **Anti-Bot Protection** | Cloudflare Turnstile, DDoS-Guard, or naked HTML? | If Cloudflare is active: use the interactive `solveCloudflare` + `fetchWithCloudflare` WebView pattern. If none: use direct `app.get()`. |
| **API vs HTML Scraping** | Does network traffic show `/api/...` JSON calls? | Always prefer internal JSON endpoints over Jsoup HTML parsing (faster, resilient to UI redesigns). |
| **Video Delivery** | Direct MP4/M3U8 or 3rd-party embed (StreamTape, MegaCloud, Vidcloud, RapidCloud)? | If 3rd-party: delegate to CloudStream's built-in `loadExtractor(url, callback)`. If custom/internal: use regex unpacker. |
| **Stream Decryption** | Does HLS `.m3u8` contain `#EXT-X-KEY:METHOD=AES-128`? | If AES-128 without explicit `IV`: plug in the sequence-based IV generator (`fixHlsAesSequenceIvs`). |
| **Artwork Quality** | Does the site provide landscape 16:9 fanart or only 200px portrait posters? | If only small portrait posters: hook AniList/AniZip fallback immediately so the Hero Billboard is not pixelated. |

---

## 3. Phase 1: Project & Gradle Architecture (5 Minutes)

### Root `build.gradle.kts`
CloudStream's gradle plugin requires strict version consistency for coroutines and runtime libraries. Use this exact configuration:

```kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "Adiversion/animepahe")
    }

    android {
        namespace = "com.lagradost.${project.name.lowercase()}"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        val compileOnly by configurations

        // Cloudstream stubs
        cloudstream("com.lagradost:cloudstream3:pre-release")
        
        // CRITICAL: Strictly align coroutines to 1.7.1 as required by Cloudstream consistent resolution
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}
```

### Provider `build.gradle.kts`
```kotlin
version = 1

cloudstream {
    language = "en"
    description = "Provider description"
    authors = listOf("Community")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://raw.githubusercontent.com/<user>/<repo>/builds/icon.png"
}
```

---

## 4. Phase 2: Core Provider Implementation (25 Minutes)

### 1. Cloudflare Turnstile & Cookie Persistence Pattern
When a provider is protected by Cloudflare Turnstile:
1. Synchronize the native Android `WebView` User-Agent with OkHttp.
2. Present a clean dialog with a progress bar and transparent background.
3. Automatically dismiss the dialog once `cf_clearance` cookie is obtained.
4. Gate concurrent threads with an `AtomicBoolean` lock so only one verification dialog opens at a time.

```kotlin
companion object {
    const val MAIN_URL = "https://target-site.com"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    var cfCookies: String? = null
    var cfUserAgent: String? = null
    private val isDialogShowing = java.util.concurrent.atomic.AtomicBoolean(false)
}
```

### 2. Main Page with High-Res Hero Banners (`getMainPage`)
CloudStream's top hero billboard expects a **16:9 landscape backdrop (1920×1080 FHD)**. If the streaming site only has low-resolution episode snapshots, fetch AniList banners in a **single batched GraphQL query**:

```kotlin
private suspend fun fetchTopBanners(titles: List<String>): Map<Int, String> {
    if (titles.isEmpty()) return emptyMap()
    return runCatching {
        val queryParts = titles.mapIndexed { idx, title ->
            val clean = title.replace(Regex("""\s*(?:Season|\(TV\)|Part)\s*\d+.*$""", RegexOption.IGNORE_CASE), "").trim()
            val escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
            "a$idx: Media(search: \"$escaped\", type: ANIME) { bannerImage coverImage { extraLarge } }"
        }.joinToString("\n")
        val resp = app.post("https://graphql.anilist.co", json = mapOf("query" to "query {\n$queryParts\n}"))
            .parsedSafe<Map<String, Map<String, AniListMedia>>>()
        resp?.get("data")?.mapNotNull { (key, media) ->
            val idx = key.removePrefix("a").toIntOrNull() ?: return@mapNotNull null
            val img = media.bannerImage ?: media.coverImage?.extraLarge ?: return@mapNotNull null
            idx to img
        }?.toMap() ?: emptyMap()
    }.getOrDefault(emptyMap())
}
```

### 3. Anime Details Pipeline (`load`)
**Never execute sequential blocking network calls.** Run episode generation and external artwork resolution in parallel:

```kotlin
override suspend fun load(url: String): LoadResponse? {
    val session = parseJson<LoadData>(url).session.takeIf { it.isNotBlank() } ?: return null
    val pageHtml = fetchWithCloudflare("$mainUrl/anime/$session")
    val doc = Jsoup.parse(pageHtml)
    val animeTitle = doc.selectFirst("h1")?.text().orEmpty()
    val poster = doc.selectFirst(".anime-poster a")?.attr("href")

    // Parallel episode list & external artwork fetching
    val (episodes, artwork) = coroutineScope {
        val epJob = async { generateListOfEpisodes(session) }
        val artJob = async { fetchArtwork(animeTitle, parsedAnilistId, parsedMalId) }
        epJob.await() to artJob.await()
    }

    val bestHeroBanner = artwork.fanartUrl ?: artwork.bannerUrl ?: artwork.highResPosterUrl ?: fixImageUrl(poster)
    val bestPoster = artwork.highResPosterUrl ?: fixImageUrl(poster)

    return newAnimeLoadResponse(animeTitle, url, tvType) {
        this.posterUrl = bestPoster
        this.backgroundPosterUrl = bestHeroBanner
        this.posterHeaders = getImageHeaders(poster ?: "$MAIN_URL/")
        this.year = year
        addEpisodes(DubStatus.Subbed, episodes)
    }
}
```

Wrap external metadata calls (AniZip / AniList) in `withTimeoutOrNull(2500L)` so slow external APIs never freeze the UI.

### 4. Parallel Stream Quality Resolution (`loadLinks`)
Sequential resolution of 360p, 720p, 1080p causes severe playback lag. Resolve all stream qualities concurrently:

```kotlin
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val episodeUrl = parseJson<LinkLoadData>(data).getUrl() ?: return false
    val html = fetchWithCloudflare(episodeUrl)
    val doc = Jsoup.parse(html)
    val buttons = doc.select("#resolutionMenu > button")

    coroutineScope {
        buttons.map { btn ->
            async {
                val kwikSrc = btn.attr("data-src").takeIf { it.isNotBlank() } ?: return@async
                val qualityText = btn.text()
                val quality = Regex("""(\d+)p""").find(qualityText)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

                getStreamUrl(kwikSrc)?.let { streamUrl ->
                    // Construct newExtractorLink OUTSIDE synchronized block
                    val link = newExtractorLink("Provider", "Provider - $qualityText", streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://stream-server.com/"
                        this.quality = quality
                    }
                    synchronized(callback) {
                        callback(link)
                    }
                }
            }
        }.awaitAll()
    }
    return true
}
```

---

## 5. Phase 3: Video Decryption & AES-128 Interceptors (10 Minutes)

### HLS AES-128 IV Auto-Derivation
If the stream host returns `#EXT-X-KEY:METHOD=AES-128` without explicit `IV=0x...`, ExoPlayer fails decryption. Derive the IV directly from the media segment sequence index:

```kotlin
private val KEY_REGEX = Regex("""^#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:\s*)$""")
private val SEGMENT_REGEX = Regex("""segment-(\d+)-""")

fun fixHlsAesSequenceIvs(m3u8: String): String {
    var currentKeyUri: String? = null
    val sb = StringBuilder(m3u8.length)
    for (line in m3u8.lines()) {
        val trimmed = line.trim()
        val keyMatch = KEY_REGEX.find(trimmed)
        if (keyMatch != null) {
            currentKeyUri = keyMatch.groupValues[1]
            continue
        }
        if (currentKeyUri != null && !trimmed.startsWith("#") && trimmed.isNotBlank()) {
            val segNum = SEGMENT_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (segNum != null) {
                val ivHex = segNum.toString(16).padStart(32, '0')
                sb.append("""#EXT-X-KEY:METHOD=AES-128,URI="$currentKeyUri",IV=0x$ivHex""").append('\n')
            }
        }
        sb.append(line).append('\n')
    }
    return sb.toString().trimEnd()
}
```

### Video Interceptor
Ensure all video chunk and key requests carry the required `Referer`:

```kotlin
override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
    if (extractorLink.type != ExtractorLinkType.M3U8 && !extractorLink.url.contains(".m3u8")) return null
    return Interceptor { chain ->
        val req = chain.request()
        val builder = req.newBuilder()
        if (req.header("Referer").isNullOrBlank()) {
            builder.header("Referer", "https://stream-server.com/")
        }
        val response = chain.proceed(builder.build())
        if (!req.url.encodedPath.endsWith(".m3u8")) return@Interceptor response
        val fixedM3u8 = fixHlsAesSequenceIvs(response.body?.string().orEmpty())
        response.newBuilder().body(fixedM3u8.toResponseBody(response.body?.contentType())).build()
    }
}
```

---

## 6. Phase 4: Common Pitfalls & Solutions Cheat Sheet

| Symptom | Root Cause | Solution |
| :--- | :--- | :--- |
| **`DateTimeFormatException: position 10`** | `addDate()` only accepts `yyyy-MM-dd` (10 chars), not `yyyy-MM-dd HH:mm:ss`. | Pass `dateStr.substringBefore(" ")` or `dateStr.take(10)`. |
| **`HTTP 403` on Coil Snapshots** | Coil doesn't get headers when loading episode items. | Set `this.posterHeaders = getImageHeaders(poster ?: "$MAIN_URL/")` on `LoadResponse`. |
| **`The 'newExtractorLink' suspension point is inside a critical section`** | Calling `suspend fun` inside Java `synchronized(callback)`. | Create `val link = newExtractorLink(...)` before entering `synchronized(callback) { callback(link) }`. |
| **`strictly 1.7.1` Gradle resolution error** | Using newer coroutine versions (`1.9.0`) incompatible with CloudStream runtime. | Set `compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")`. |
| **Large `.cs3` file size (>1 MB)** | Using `implementation()` for libraries already bundled in CloudStream. | Use `compileOnly()` for coroutines and CloudStream core dependencies. |

---

## 7. Cleanroom Decompilation Workflow (Closed-Source to Open-Source)

When porting a proprietary `.cs3` / `.apk` to open source:

1. **Unzip & Disassemble**:
   ```bash
   unzip Provider.cs3 -d output_dir
   jadx -d decompiled_src output_dir/classes.dex
   ```
2. **Strip Obfuscated Boilerplate**: Remove R8/ProGuard synthetic classes (`$$ExternalSyntheticLambda`, `$r8$lambda`).
3. **Isolate Logic**:
   - Extract the search query format.
   - Extract episode list pagination logic.
   - Extract the video stream unpacker / regex.
4. **Implement Cleanroom Provider**: Rewrite using the 4 core methods above with zero decompiled artifacts.
5. **Verify Against CI**: Push to GitHub Actions and confirm `plugins.json` hash and file size.
