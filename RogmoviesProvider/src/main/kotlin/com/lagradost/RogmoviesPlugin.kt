package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RogmoviesPlugin : Plugin() {
    override fun load(context: Context) {
        // Register Rogmovies MainAPI provider
        registerMainAPI(RogmoviesProvider())
        // Register Bollydrive Extractor
        registerExtractorAPI(BollydriveExtractor())
    }
}
