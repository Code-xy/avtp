package com.seagull.avtp

import android.app.Application
import android.webkit.CookieManager
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // WebView 与 OkHttp/ExoPlayer 共享同一个 Cookie 容器：
        // 解析线路的流地址是会话绑定的，嗅探到的 URL 必须带上 WebView 的会话 cookie 才能播。
        CookieManager.getInstance().setAcceptCookie(true)

        // GSY 默认内核是 IjkPlayerManager，它的静态字段会去加载 libijk*.so。
        // 我们裁剪掉了那些 so，所以必须显式切到 Exo2（底层就是 media3 ExoPlayer）。
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)
    }
}
