package com.seagull.avtp.parse

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 抓流探针。
 *
 * 为什么必须有它：解析线路的 m3u8 是页面 JS 自己算签名/解密/发请求拿到的，
 * 纯 HTTP 客户端抽不到（实测 3 条现役线路全部带 JSJiami / crypto-js / 反调试）。
 * 只有让真 JS 引擎跑起来，才能从网络层把流地址截下来。
 *
 * 定位是「探针」不是「播放器」：一旦嗅到流，立刻回调，上层销毁 WebView，
 * 播放交给 ExoPlayer —— 用户看到的是原生播放器。
 */
class WebViewProbe(
    private val onFound: (stream: String) -> Unit,
    private val onFail: (reason: String) -> Unit,
) {
    private var webView: WebView? = null
    private val done = AtomicBoolean(false)
    private val seen = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** 自动点播放：不同线路按钮选择器不同，挨个试 */
    private val autoClickJs = """
    (function(){
      var sels=['#btn','#playbtn','.play-btn','.btn-play','button.play','#play','.dplayer-play-icon','.artplayer-plugin'];
      for(var i=0;i<sels.length;i++){
        var e=document.querySelector(sels[i]);
        if(e&&e.offsetParent!==null){try{e.click();return sels[i];}catch(x){}}
      }
      var bs=document.querySelectorAll('button');
      for(var j=0;j<bs.length;j++){
        var t=(bs[j].innerText||'').trim();
        if(t.indexOf('播放')>=0){try{bs[j].click();return 'text:'+t;}catch(x){}}
      }
      return null;
    })();
    """.trimIndent()

    private val clickTask = object : Runnable {
        override fun run() {
            if (done.get()) return
            runCatching { webView?.evaluateJavascript(autoClickJs, null) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(host: ViewGroup, pageUrl: String, referer: String, timeoutMs: Long = 30000) {
        val v = WebView(host.context)
        webView = v

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(v, true)

        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = Http.UA
        }

        v.webViewClient = object : WebViewClient() {
            // 网络层嗅探 —— 比读 DOM 可靠得多，JS 用 fetch/XHR/hls.js 拿的流都逃不掉
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                runCatching { hit(request.url.toString()) }
                return null // 放行，不影响页面正常加载
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 播放按钮通常需要用户手势，多点几次
                for (d in longArrayOf(600, 1500, 3000, 5000, 8000)) {
                    main.postDelayed(clickTask, d)
                }
            }
        }

        // 1x1 离屏，不干扰播放器 UI
        host.addView(v, ViewGroup.LayoutParams(1, 1))
        runCatching { v.loadUrl(pageUrl, mapOf("Referer" to referer)) }

        main.postDelayed({
            if (done.compareAndSet(false, true)) onFail("超时 ${timeoutMs / 1000}s：没嗅到流地址")
        }, timeoutMs)
    }

    private fun hit(url: String) {
        if (done.get() || !isStream(url)) return
        if (!seen.add(url.substringBefore("?"))) return
        if (done.compareAndSet(false, true)) {
            cancelTimers()
            main.post { onFound(url) }
        }
    }

    /** 识别"播放列表/整片"级请求，避免把 .ts 分片当成流 */
    private fun isStream(u: String): Boolean {
        val s = u.lowercase()
        if (s.contains(".m3u8")) return true
        if (s.contains("type=playlist")) return true
        if (s.contains(".mp4") || s.contains(".flv")) return true
        return false
    }

    fun cancelTimers() {
        main.removeCallbacksAndMessages(null)
    }

    /** 拿到流后立刻退场，避免 WebView 继续吃内存/弹广告 */
    fun destroy() {
        cancelTimers()
        runCatching {
            webView?.stopLoading()
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
        }
        webView = null
    }
}
