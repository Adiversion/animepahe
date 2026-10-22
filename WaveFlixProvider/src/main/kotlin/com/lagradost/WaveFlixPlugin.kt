package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WaveFlixPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the WaveFlix MainAPI provider
        registerMainAPI(WaveFlixProvider())

        // Register direct Indian CDN & Cloud Extractors
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(NexdriveExtractor())
        registerExtractorAPI(NexdriveYouExtractor())
        registerExtractorAPI(NexdriveStoreExtractor())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(FastDlBeerExtractor())
        registerExtractorAPI(FastDlLolExtractor())
        registerExtractorAPI(ZeeDlExtractor())
        registerExtractorAPI(DriveSeedExtractor())
        registerExtractorAPI(HubdriveExtractor())
        registerExtractorAPI(HubcloudExtractor())
        registerExtractorAPI(BollydriveExtractor())
        registerExtractorAPI(BollydriveMomExtractor())
        registerExtractorAPI(GroundbanksExtractor())
        registerExtractorAPI(TvlogyExtractor())
        registerExtractorAPI(TvarticlesExtractor())
        registerExtractorAPI(CineverseExtractor())
        registerExtractorAPI(FilmuExtractor())

        // KatMovieHD auxiliary extractors
        registerExtractorAPI(FuckingfastExtractor())
        registerExtractorAPI(KatdriveExtractor())

        // HDHub4u & Greenmotors auxiliary extractors
        registerExtractorAPI(GreenmotorsExtractor())
        registerExtractorAPI(GreenmotorsClubExtractor())
        registerExtractorAPI(HblinksExtractor())
        registerExtractorAPI(HubcdnExtractor())
    }
}
