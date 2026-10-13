package com.seagull.avtp.player

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import okhttp3.Request
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
    private val retried = HashSet<String>()
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
        b.statusText.text = "${line.name} 已出流，校验播放头…"
        // 各线路对 Referer 的要求不一样：有的认自己域名，有的认原站，有的完全不要。
        // 拿到的流地址先挨个试，(能拉到 #EXTM3U 头) 的那个才拿去播。
        Thread {
            val working = pickReferer(stream, line)
            runOnUiThread {
                if (working == null) {
                    setStatus(line.name, "流不可播")
                    b.loading.visibility = View.VISIBLE
                    b.statusText.text = "${line.name}：流地址拿回来了但拉不到播放列表"
                    if (autoSwitchOn) nextUntriedLine()
                } else {
                    setStatus(line.name, "可用")
                    play(stream, line, working)
                }
            }
        }.start()
    }

    /** 逐个候选 Referer 试拉 m3u8，返回第一个能返回 #EXTM3U 的 */
    private fun pickReferer(stream: String, line: Line): String? {
        val candidates = LinkedHashSet<String>()
        candidates.add(line.referer)
        candidates.add("")
        platformOrigin()?.let { candidates.add(it) }
        candidates.add("https://jx.202617.xyz/")
        for (r in candidates) if (loadsPlaylist(stream, r)) return r
        return null
    }

    private fun loadsPlaylist(url: String, referer: String): Boolean = try {
        val rb = Request.Builder().url(url).header("User-Agent", Http.UA)
        if (referer.isNotBlank()) rb.header("Referer", referer)
        Http.client.newCall(rb.build()).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
            } else false
        }
    } catch (e: Exception) {
        false
    }

    /** 原视频站的 origin，有些线路认这个做防盗链 */
    private fun platformOrigin(): String? = runCatching {
        val h = java.net.URI(videoUrl).host ?: return@runCatching null
        "https://$h/"
    }.getOrNull()

    private fun play(stream: String, line: Line, referer: String) {
        val p = ensurePlayer(referer)
        // ★ 关键：解析线路给的流地址大多没有 .m3u8 后缀（形如 /global?...&type=playlist），
        //   ExoPlayer 光看 URI 推断不出类型，会当成普通视频文件用 ProgressiveMediaSource 打开，
        //   报 UnrecognizedInputFormatException。必须显式指定 MIME。
        val item = MediaItem.Builder()
            .setUri(stream)
            .setMimeType(mimeFor(stream))
            .build()
        p.setMediaItem(item)
        p.seekTo(resumeMs)
        p.prepare()
        p.playWhenReady = true
        b.loading.visibility = View.GONE
        renderPanel()
        // 流地址已到 ExoPlayer 手里，探针立刻退场（cookie 在全局 CookieManager 里，不会丢）
        probe?.destroy()
        probe = null
    }

    private fun onResolveFail(reason: String, line: Line) {
        setStatus(line.name, "失效")
        b.statusText.text = "${line.name}：$reason"
        b.loading.visibility = View.VISIBLE
        probe?.destroy()
        probe = null
        if (autoSwitchOn) nextUntriedLine()
    }

    private fun mimeFor(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") || u.contains("type=playlist") -> MimeTypes.APPLICATION_M3U8
            u.contains(".mp4") -> MimeTypes.VIDEO_MP4
            u.contains(".flv") -> MimeTypes.VIDEO_FLV
            else -> MimeTypes.APPLICATION_M3U8
        }
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
            val name = lines.getOrNull(lineIndex)?.name.orEmpty()
            b.loading.visibility = View.VISIBLE
            toast("播放出错：${error.errorCodeName}")
            // 先原线路重试一次（流地址带了短时令牌，常是偶发取流不及时），再考虑换线路
            if (name.isNotBlank() && retried.add(name)) {
                b.statusText.text = "$name 播放出错，重试中…"
                retried.add(name)
                startResolve()
                return
            }
            setStatus(name, "播放失败")
            b.statusText.text = "播放出错：${error.errorCodeName}"
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
