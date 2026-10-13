package com.seagull.avtp.parse

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * CookieJar 桥接：读写的是 WebView 的 CookieManager。
     * 这样 WebView 探针建立的会话，ExoPlayer 拉流时自动带上。
     */
    private val webCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            for (c in cookies) runCatching { cm.setCookie(url.toString(), c.toString()) }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cm = CookieManager.getInstance()
            val raw = runCatching { cm.getCookie(url.toString()) }.getOrNull() ?: return emptyList()
            return raw.split("; ").mapNotNull { runCatching { Cookie.parse(url, it) }.getOrNull() }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(webCookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}
