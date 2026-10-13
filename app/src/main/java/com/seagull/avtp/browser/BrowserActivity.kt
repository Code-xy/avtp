package com.seagull.avtp.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seagull.avtp.databinding.ActivityBrowserBinding
import com.seagull.avtp.parse.Http
import com.seagull.avtp.parse.Platform
import com.seagull.avtp.parse.UrlNormalizer
import com.seagull.avtp.player.PlayerActivity

/**
 * 官网适配页 —— 内置浏览器直接逛爱奇艺/优酷/腾讯官网。
 *
 * 用户在官网上正常找片、点进播放页，底部「解析播放」一键把当前页面
 * 丢给解析线路，转到原生播放器。
 *
 * 之所以不用官方 App 的分享跳转：那样要来回切应用，体验割裂。
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private var platform: Platform = Platform.IQIYI

    // 各平台的视频详情页特征 —— 命中就把「解析播放」点亮
    private val videoRx: Map<Platform, Regex> = mapOf(
        Platform.IQIYI to Regex("""iqiyi\.com/(v_|a_|w_)[0-9a-zA-Z]+\.html""", RegexOption.IGNORE_CASE),
        Platform.YOUKU to Regex("""youku\.com/(v_show/id_|video)[A-Za-z0-9=]+""", RegexOption.IGNORE_CASE),
        Platform.QQ    to Regex("""v\.qq\.com/x/(cover|page)/[0-9a-zA-Z]+\.html|x/m/play\?""", RegexOption.IGNORE_CASE),
    )

    private val home: Map<Platform, String> = mapOf(
        Platform.IQIYI to "https://m.iqiyi.com/",
        Platform.YOUKU to "https://m.youku.com/",
        Platform.QQ    to "https://m.v.qq.com/",
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)

        platform = when (intent.getStringExtra(EXTRA_PLATFORM)) {
            "youku" -> Platform.YOUKU
            "qq" -> Platform.QQ
            else -> Platform.IQIYI
        }
        b.browserTitle.text = "${platform.label}官网"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = Http.UA
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(b.web, true)

        b.web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                b.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                b.progress.visibility = View.GONE
                syncUrl(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean {
                val u = request.url.toString()
                // 站外跳转（广告/下载）拦掉，只在本页加载
                if (u.startsWith("http") && samePlatform(u)) return false
                return !u.startsWith("http") // 非 http 一律交给系统
            }
        }
        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        b.btnBack.setOnClickListener { if (b.web.canGoBack()) b.web.goBack() else finish() }
        b.btnParse.setOnClickListener { parseCurrent() }
        b.btnReload.setOnClickListener { b.web.reload() }

        val start = intent.getStringExtra(EXTRA_URL)
        b.web.loadUrl(start?.takeIf { it.startsWith("http") } ?: home.getValue(platform))
    }

    private fun samePlatform(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.lowercase()
        when (platform) {
            Platform.IQIYI -> "iqiyi" in host || "iq.com" in host
            Platform.YOUKU -> "youku" in host || "tudou" in host
            Platform.QQ -> "qq.com" in host
            else -> false
        }
    }.getOrDefault(false)

    private fun syncUrl(url: String) {
        b.urlText.text = url
        val hit = videoRx[platform]?.containsMatchIn(url) == true
        b.btnParse.isEnabled = true
        b.btnParse.alpha = if (hit) 1f else 0.55f
        if (hit) b.tip.text = "已识别到播放页，点右下角「解析播放」"
        else b.tip.text = "进到剧集播放页再点「解析播放」"
    }

    private fun parseCurrent() {
        val raw = b.web.url ?: return
        val n = UrlNormalizer.normalize(raw)
        if (n == null) {
            toast("当前页不是 ${platform.label} 的播放页，先点进剧集")
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, n.second)
                .putExtra(PlayerActivity.EXTRA_TITLE, "${n.first.label} · 官网解析")
                .putExtra(PlayerActivity.EXTRA_LINE_INDEX, 0)
        )
    }

    override fun onDestroy() {
        runCatching { b.web.destroy() }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (b.web.canGoBack()) b.web.goBack() else super.onBackPressed()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_URL = "url"
    }
}
