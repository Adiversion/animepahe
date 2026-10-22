# AnimePahe CloudStream Extension

AnimePahe provider plugin for [CloudStream 3](https://github.com/recloudstream/cloudstream).

## Features
- **Browse**: Airing releases and latest episodes on the main page.
- **Search**: Fast anime search with titles and poster thumbnails.
- **Details**: Synopses, air years, episode counts, genres, MAL and AniList external IDs.
- **Playback & Downloads**: High-speed streaming and downloads via unpacked Kwik (`.m3u8` / `.mp4`) and Pahe stream resolvers.

---

## How to Install in CloudStream

1. **Host on GitHub**:
   - Repository: `https://github.com/Adiversion/csext`
   - Go to your repository **Settings** > **Actions** > **General** > **Workflow permissions** and select **Read and write permissions**.
   - Trigger the build workflow by pushing a commit. GitHub Actions will compile the extension and publish it to the `builds` branch.

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

   - Tap **Download**, locate **AnimePahe** in the extension list, and click **Install**.

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
