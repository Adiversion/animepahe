package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BollyStreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Register BollyStream MainAPI provider
        registerMainAPI(BollyStreamProvider())
        // Register Bollydrive Extractors
        registerExtractorAPI(BollydriveExtractor())
        registerExtractorAPI(BollydriveMomExtractor())
        // Register High-Speed Stream & CDN Extractors
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(NexdriveExtractor())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(DriveSeedExtractor())
    }
}
