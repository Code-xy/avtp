package com.seagull.avtp.player

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.seagull.avtp.databinding.ActivityPlayerBinding
import com.seagull.avtp.parse.Http
import com.seagull.avtp.parse.Line
import com.seagull.avtp.parse.LinePool
import com.seagull.avtp.parse.WebViewProbe
import java.util.concurrent.TimeUnit

/**
 * 播放页：原生 ExoPlayer + 右下角线路浮层。
 *
 * 切换线路的原则是「无感」：先解析出新流，再 setMediaItem 并 seekTo 到切之前的进度。
 * 所以切换时不能 release 播放器黑屏，只能换 media source。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var probe: WebViewProbe? = null

    private var videoUrl = ""
    private var title = ""
    private var lineIndex = 0
    private var resumeMs = 0L
    private var panelOpen = false
    private var curReferer: String? = null

    private val lineStatus = HashMap<String, String>()
    private val tried = LinkedHashSet<String>()
    private val autoSwitchOn = true

    private val lines: List<Line> get() = LinePool.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, 0).coerceIn(0, lines.lastIndex)

        if (videoUrl.isBlank()) {
            finish()
            return
        }
        b.titleText.text = title.ifBlank { "解析播放" }
        b.btnLines.setOnClickListener { togglePanel() }
        renderPanel()
        startResolve()
    }

    // ── 取流 ────────────────────────────────────────────────────────────

    private fun startResolve() {
        probe?.destroy()
        val line = lines[lineIndex]
        tried.add(line.name)
        setStatus(line.name, "解析中…")
        b.loading.visibility = View.VISIBLE
        b.statusText.text = "正在解析：${line.name}"

        val p = WebViewProbe(
            onFound = { stream -> runOnUiThread { onStreamFound(stream, line) } },
            onFail = { reason -> runOnUiThread { onResolveFail(reason, line) } },
            onProgress = { stage ->
                runOnUiThread {
                    if (b.loading.visibility == View.VISIBLE) {
                        b.statusText.text = "${line.name} · $stage"
                    }
                }
            },
        )
        probe = p
        p.start(b.probeHost, line.buildUrl(videoUrl), line.referer, TIMEOUT_MS)
    }

    private fun onStreamFound(stream: String, line: Line) {
        setStatus(line.name, "可用")
        b.statusText.text = "${line.name} 已出流，正在加载…"
        play(stream, line)
    }

    private fun onResolveFail(reason: String, line: Line) {
        setStatus(line.name, "失效")
        b.statusText.text = "${line.name}：$reason"
        b.loading.visibility = View.VISIBLE
        probe?.destroy()
        probe = null
        if (autoSwitchOn) nextUntriedLine()
    }

    private fun play(stream: String, line: Line) {
        val p = ensurePlayer(line.referer)
        p.setMediaItem(MediaItem.fromUri(stream))
        p.seekTo(resumeMs)
        p.prepare()
        p.playWhenReady = true
        b.loading.visibility = View.GONE
        renderPanel()
        // 流地址已到 ExoPlayer 手里，探针立刻退场（cookie 在全局 CookieManager 里，不会丢）
        probe?.destroy()
        probe = null
    }

    /**
     * 关键：解析出来的流是**会话绑定的**（实测不带 Referer 直接 403），
     * 所以必须把线路的 Referer 透传给 ExoPlayer，且共享 WebView 的 CookieJar。
     */
    private fun ensurePlayer(referer: String): ExoPlayer {
        player?.let { if (curReferer == referer) return it }

        player?.release()
        player = null

        val headers = HashMap<String, String>()
        headers["User-Agent"] = Http.UA
        headers["Referer"] = referer

        val dsFactory = OkHttpDataSource.Factory(Http.client)
            .setDefaultRequestProperties(headers)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(playerListener)
        p.setHandleAudioBecomingNoisy(true)
        b.playerView.player = p
        curReferer = referer
        player = p
        return p
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            resumeMs = player?.currentPosition ?: resumeMs
            toast("播放出错：${error.errorCodeName}")
            b.loading.visibility = View.VISIBLE
            b.statusText.text = "播放出错：${error.errorCodeName}，尝试下一条线路…"
            if (autoSwitchOn) nextUntriedLine()
        }
    }

    // ── 线路切换 ────────────────────────────────────────────────────────

    private fun switchLine(i: Int) {
        if (i == lineIndex && player != null) {
            togglePanel()
            return
        }
        resumeMs = player?.currentPosition ?: resumeMs
        lineIndex = i
        panelOpen = false
        renderPanel()
        startResolve()
    }

    private fun nextUntriedLine() {
        val next = lines.indices.firstOrNull { lines[it].name !in tried }
        if (next == null) {
            b.loading.visibility = View.VISIBLE
            b.statusText.text = "所有线路都失败了（共 ${lines.size} 条）"
            return
        }
        lineIndex = next
        renderPanel()
        startResolve()
    }

    private fun togglePanel() {
        panelOpen = !panelOpen
        renderPanel()
    }

    private fun renderPanel() {
        b.lineList.removeAllViews()
        lines.forEachIndexed { i, line ->
            val st = lineStatus[line.name].orEmpty()
            val tv = TextView(this).apply {
                val active = i == lineIndex
                text = buildString {
                    append(if (active) "▶ " else "${i + 1}. ")
                    append(line.name)
                    if (st.isNotEmpty()) append("   · $st")
                }
                setTextColor(if (active) 0xFF4DA3FF.toInt() else 0xFFECECEC.toInt())
                textSize = 13.5f
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener { switchLine(i) }
            }
            b.lineList.addView(tv)
        }
        b.linePanel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        b.currentLineText.text = lines.getOrNull(lineIndex)?.name.orEmpty()
    }

    private fun setStatus(name: String, s: String) {
        lineStatus[name] = s
        renderPanel()
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        resumeMs = player?.currentPosition ?: resumeMs
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        probe?.destroy()
        probe = null
        player?.release()
        player = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LINE_INDEX = "line_index"
        private const val TIMEOUT_MS = 40_000L
    }
}
