# csext — CloudStream Extensions

Production-grade CloudStream 3 provider extensions repository featuring:
- **AnimePahe**: Fast anime streaming and downloads via unpacked Kwik and Pahe stream resolvers.
- **WaveFlix**: Full Movies, TV Series, and Anime streaming via multi-server playback engine (Nxsha, ScreenScape, Peachify, VidSrc, AutoEmbed).

---

## Features
- **Browse**: Airing anime, trending movies, TV shows, and top-rated media catalog feeds.
- **Search**: High-speed search with TMDB poster thumbnails and synopses.
- **Details**: Full season and episode listings, synopsis, release years, and artwork.
- **Multi-Server Streams**: Redundant stream extraction across multiple high-speed hosts.

---

## How to Install in CloudStream

1. **Host on GitHub**:
   - Repository: `https://github.com/Adiversion/csext`
   - Trigger the build workflow by pushing a commit. GitHub Actions will compile the extensions and publish them to the `builds` branch.

2. **Add Repository to CloudStream**:
   - Open CloudStream on your Android device / Android TV.
   - Go to **Settings** > **Extensions** > **Add Repository**.
   
   - **Method A (CloudStream Numeric Shortcode - Remote Keypad Friendly):**
     In the **Repository URL or Shortcode** field, simply enter:
     ```text
     3253
     ```
     *(Resolves via `https://cutt.ly/3253` to the repository manifest)*

   - **Method B (Short URL):**
     ```text
     https://tinyurl.com/2xr6vygq
     ```

   - **Method C (Direct Full URL):**
     ```text
     https://raw.githubusercontent.com/Adiversion/csext/builds/repo.json
     ```
   
   - **Method D (One-Click Auto-Add on Android):**  
     Tap this link on your Android phone or tablet:  
     [cloudstreamrepo://https://raw.githubusercontent.com/Adiversion/csext/builds/repo.json](cloudstreamrepo://https://raw.githubusercontent.com/Adiversion/csext/builds/repo.json)

   - Tap **Download**, locate **AnimePahe** or **WaveFlix** in the extension list, and click **Install**.

---

## Local Development & Testing

### Prerequisites
- JDK 17+ (or Android Studio)
- Android SDK (API 35, minSdk 21)

### Build Commands
* **Windows**:
  ```powershell
  .\gradlew.bat AnimePaheProvider:make
  ```
* **Direct ADB install to connected Android device**:
  ```powershell
  .\gradlew.bat AnimePaheProvider:deployWithAdb
  ```
* **Build all plugins and manifest**:
  ```powershell
  .\gradlew.bat make makePluginsJson
  ```

---

## Project Structure
```text
├── .github/workflows/build.yml      # CI/CD: builds .cs3 plugins and deploys to 'builds' branch
├── AnimePaheProvider/               # Extension module
│   ├── build.gradle.kts             # Plugin metadata (version, status, authors, tvTypes)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/lagradost/
│           ├── AnimePahePlugin.kt   # Cloudstream plugin entrypoint
│           └── AnimePaheProvider.kt # MainAPI scraper and stream extractor
├── build.gradle.kts                 # Root Gradle build script
├── repo.json                        # CloudStream repository manifest
├── settings.gradle.kts              # Module auto-discovery
└── gradle/wrapper/                  # Gradle 8.12 wrapper
```

## License
Released into the public domain under The Unlicense.
