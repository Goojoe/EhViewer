package com.hippo.ehviewer.util

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.Settings

private const val MINIMUM_WEBVIEW_VERSION = 118
val WebViewVersion = run {
    val context = EhApplication.application
    val uaVersion = runCatching {
        Regex("Chrome/(\\d+)")
            .find(WebSettings.getDefaultUserAgent(context))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }.getOrNull()
    val pkgVersion = WebViewCompat
        .getCurrentWebViewPackage(context)
        ?.versionName
        ?.substringBefore('.')
        ?.toIntOrNull()
    uaVersion ?: pkgVersion ?: MINIMUM_WEBVIEW_VERSION
}
var isWebViewOutdated = WebViewVersion < MINIMUM_WEBVIEW_VERSION

@SuppressLint("SetJavaScriptEnabled")
fun WebView.setDefaultSettings() = settings.run {
    builtInZoomControls = true
    displayZoomControls = false
    javaScriptEnabled = true
    domStorageEnabled = true
    userAgentString = Settings.userAgent!!
}

@OptIn(Profile.ExperimentalAddQuicHints::class)
fun addQuicHints(url: String) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ADD_QUIC_HINTS_V1) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    ) {
        val defPro = ProfileStore.getInstance().getProfile(Profile.DEFAULT_PROFILE_NAME)
        defPro?.addQuicHints(setOf(url))
    } else {
        isWebViewOutdated = true
    }
}
