package com.hippo.okhttp

import com.hippo.ehviewer.client.exception.CloudflareBypassException
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

class CloudflareInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.header("cf-mitigated") == "challenge") {
            throw IOException(CloudflareBypassException())
        }
        return response
    }
}
