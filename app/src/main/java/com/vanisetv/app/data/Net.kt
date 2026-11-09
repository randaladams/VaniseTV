package com.vanisetv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    const val USER_AGENT = "VaniseTV/1.0 (Fire TV; +https://github.com/randaladams/VaniseTV)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    /** Blocking GET; call from a background dispatcher. */
    fun getText(url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    fun getBytes(url: String): ByteArray {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.bytes() ?: throw IOException("Empty body for $url")
        }
    }
}
