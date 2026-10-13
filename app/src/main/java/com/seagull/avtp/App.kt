package com.seagull.avtp

import android.app.Application
import android.webkit.CookieManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // WebView 与 OkHttp/ExoPlayer 共享同一个 Cookie 容器：
        // 解析线路的流地址是会话绑定的，嗅探到的 URL 必须带上 WebView 的会话 cookie 才能播。
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
