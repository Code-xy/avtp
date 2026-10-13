package com.seagull.avtp.parse

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView 抓流探针。
 *
 * 为什么必须有它：解析线路的 m3u8 是页面 JS 自己算签名/解密/发请求拿到的，
 * 纯 HTTP 客户端抽不到（实测 3 条现役线路全部带 JSJiami / crypto-js / 反调试）。
 *
 * 定位是「探针」不是「播放器」：嗅到流立刻回调，上层销毁 WebView，播放交 ExoPlayer。
 */
class WebViewProbe(
    private val onFound: (stream: String) -> Unit,
    private val onFail: (reason: String) -> Unit,
    private val onProgress: (String) -> Unit = {},
) {
    private var webView: WebView? = null
    private val done = AtomicBoolean(false)
    private val seen = HashSet<String>()
    private val reqCount = AtomicInteger(0)
    private val clickCount = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 自动点播放。用 dispatchEvent 而不是 .click()，
     * 因为不少播放器只监听了真实的 addEventListener('click')。
     */
    private val autoClickJs = """
    (function(){
      function fire(el){
        try{ el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window})); return 'dispatch'; }catch(e){}
        try{ el.click(); return 'click'; }catch(e){}
        return null;
      }
      var sels=['#btn','#playbtn','.play-btn','button.play','.btn-play','#play','.dplayer-play-icon','.artplayer-plugin'];
      for(var i=0;i<sels.length;i++){
        var e=document.querySelector(sels[i]);
        if(e){ var r=fire(e); if(r) return sels[i]+':'+r; }
      }
      var bs=document.querySelectorAll('button,a,div');
      for(var j=0;j<bs.length&&j<400;j++){
        var t=((bs[j].innerText||'')+'').trim();
        if(t.indexOf('播放')>=0 && t.length<12){ var r=fire(bs[j]); if(r) return 'txt:'+t+':'+r; }
      }
      var v=document.querySelector('video'); if(v){ var r=fire(v); if(r) return 'video:'+r; }
      return 'none';
    })();
    """.trimIndent()

    private val clickTask = object : Runnable {
        override fun run() {
            if (done.get()) return
            runCatching {
                webView?.evaluateJavascript(autoClickJs) { r ->
                    val s = r?.trim('"') ?: "?"
                    Log.i(TAG, "自动点击 #${clickCount.incrementAndGet()} -> $s")
                    if (s != "none" && s != "null") onProgress("已触发播放按钮（$s）")
                    else onProgress("页面已加载，等待流地址…")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(host: ViewGroup, pageUrl: String, referer: String, timeoutMs: Long = 40000) {
        val v = WebView(host.context)
        webView = v
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true)

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
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.i(TAG, "onPageStarted: $url")
                onProgress("页面加载中…")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "onPageFinished: $url")
                onProgress("页面已就绪，尝试触发播放…")
                for (d in longArrayOf(500, 1200, 2500, 4500, 7000, 11000, 16000)) {
                    main.postDelayed(clickTask, d)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError,
            ) {
                val d = if (Build.VERSION.SDK_INT >= 23) error.description?.toString() else "?"
                Log.w(TAG, "onReceivedError main=${request.isForMainFrame} ${request.url} -> $d")
                if (request.isForMainFrame) onProgress("页面加载失败：$d")
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, resp: WebResourceResponse,
            ) {
                Log.w(TAG, "onReceivedHttpError ${resp.statusCode} ${request.url}")
            }

            // 网络层嗅探 —— JS 用 fetch/XHR/hls.js 拿的流全逃不掉
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val u = request.url.toString()
                val n = reqCount.incrementAndGet()
                if (n <= 60) Log.i(TAG, "REQ[$n] $u")
                runCatching { hit(u) }
                return null
            }
        }

        Log.i(TAG, "probe start: $pageUrl  (referer=$referer)")
        onProgress("探针已加载线路页…")
        host.addView(v, ViewGroup.LayoutParams(-1, -1))
        runCatching { v.loadUrl(pageUrl, mapOf("Referer" to referer)) }

        main.postDelayed({
            if (done.compareAndSet(false, true)) {
                Log.w(TAG, "probe 超时")
                onFail("超时 ${timeoutMs / 1000}s：没嗅到流地址（收到 $reqCount 个请求）")
            }
        }, timeoutMs)
    }

    private fun hit(url: String) {
        if (done.get() || !isStream(url)) return
        if (!seen.add(url.substringBefore("?"))) return
        Log.i(TAG, "★ 嗅到流: $url")
        if (done.compareAndSet(false, true)) {
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

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        runCatching {
            webView?.stopLoading()
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
        }
        webView = null
    }

    companion object {
        const val TAG = "AvtpProbe"
    }
}
