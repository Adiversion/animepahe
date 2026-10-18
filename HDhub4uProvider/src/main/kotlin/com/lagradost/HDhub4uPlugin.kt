package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDhub4uPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the HDhub4u MainAPI provider
        registerMainAPI(HDhub4uProvider())
    }
}
