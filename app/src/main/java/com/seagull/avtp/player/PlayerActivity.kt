package com.seagull.avtp.player

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seagull.avtp.api.Episode
import com.seagull.avtp.api.PlatformApi
import com.seagull.avtp.databinding.ActivityPlayerBinding
import com.seagull.avtp.parse.Http
import com.seagull.avtp.parse.Line
import com.seagull.avtp.parse.LinePool
import com.seagull.avtp.parse.WebViewProbe
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 播放页：GSYVideoPlayer（Exo2 内核，自带手势音量/亮度/进度、倍速、横屏全屏）
 *        + 右下角线路切换 + 顶部选集。
 *
 * 取流仍然是「WebView 探针嗅 m3u8 → 流预检找对 Referer → 交给播放器」。
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var probe: WebViewProbe? = null
    private var orientationUtils: OrientationUtils? = null

    private var videoUrl = ""
    private var title = ""
    private var albumId = ""
    private var lineIndex = 0
    private var resumeMs = 0L
    private var linePanelOpen = false
    private var autoSwitchOn = true

    private val lineStatus = HashMap<String, String>()
    private val tried = LinkedHashSet<String>()
    private val retried = HashSet<String>()
    private val lines: List<Line> get() = LinePool.DEFAULT

    // 选集
    private var episodes: List<Episode> = emptyList()
    private var epIndex = -1

    private val aliveClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        albumId = intent.getStringExtra(EXTRA_ALBUM_ID).orEmpty()
        lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, 0).coerceIn(0, lines.lastIndex)
        if (videoUrl.isBlank()) { finish(); return }

        setupPlayer()
        b.avtpBtnLines.setOnClickListener { toggleLinePanel() }
        b.avtpBtnEps.setOnClickListener { toggleEpsPanel() }
        b.avtpBtnPrev.setOnClickListener { gotoEpisode(epIndex - 1) }
        b.avtpBtnNext.setOnClickListener { gotoEpisode(epIndex + 1) }
        renderPanel()
        loadEpisodes()
        startResolve("首次")
    }

    // ── 播放器 ──────────────────────────────────────────────────────────

    private fun setupPlayer() {
        b.avtpPlayer.setIsTouchWiget(true)
        b.avtpPlayer.setIsTouchWigetFull(true)
        b.avtpPlayer.setDismissControlTime(3000)
        b.avtpPlayer.setShowFullAnimation(true)
        b.avtpPlayer.setRotateViewAuto(false)
        b.avtpPlayer.setLockLand(false)
        b.avtpPlayer.getTitleTextView().text = title

        orientationUtils = OrientationUtils(this, b.avtpPlayer).apply { setEnable(true) }
        b.avtpPlayer.getFullscreenButton()?.setOnClickListener { orientationUtils?.resolveByClick() }
        b.avtpPlayer.getBackButton()?.setOnClickListener { handleBack() }
    }

    private fun handleBack() {
        if ((orientationUtils?.getIsLand() ?: 0) > 0) orientationUtils?.backToProtVideo() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    /**
     * 交给 GSY 播。
     * ★ 解析线路给的流地址常常没有 .m3u8 后缀（形如 /global?...&type=playlist），
     *   播放器光看 URI 推断不出类型，必须显式告诉它这是 HLS。
     */
    private fun gsyPlay(stream: String, referer: String) {
        val p = b.avtpPlayer
        runCatching { p.release() }
        p.setMapHeadData(hashMapOf("Referer" to referer, "User-Agent" to Http.UA))
        if (!stream.lowercase().contains(".m3u8") && !stream.lowercase().contains(".mp4")) {
            runCatching { p.setOverrideExtension("m3u8") }
        } else {
            runCatching { p.setOverrideExtension(null) }
        }
        p.setUp(stream, false, null, title)
        p.startPlayLogic()
        if (resumeMs > 0) runCatching { p.seekTo(resumeMs) }
        b.avtpLoading.visibility = View.GONE
    }

    // ── 取流 ────────────────────────────────────────────────────────────

    private fun startResolve(reason: String) {
        probe?.destroy()
        val line = lines[lineIndex]
        tried.add(line.name)
        Log.i(TAG, "resolve[$reason] line=${line.name} tried=$tried")
        setStatus(line.name, "解析中…")
        b.avtpLoading.visibility = View.VISIBLE
        b.avtpStatus.text = "正在解析：${line.name}"

        val p = WebViewProbe(
            onFound = { stream -> runOnUiThread { onStreamFound(stream, line) } },
            onFail = { r -> runOnUiThread { onResolveFail(r, line) } },
            onProgress = { stage ->
                runOnUiThread {
                    if (b.avtpLoading.visibility == View.VISIBLE) b.avtpStatus.text = "${line.name} · $stage"
                }
            },
        )
        probe = p
        p.start(b.avtpProbeHost, line.buildUrl(videoUrl), line.referer, TIMEOUT_MS)
    }

    private fun onStreamFound(stream: String, line: Line) {
        Log.i(TAG, "出流[${line.name}] $stream")
        b.avtpStatus.text = "${line.name} 已出流，校验播放头…"
        Thread {
            val working = pickReferer(stream, line)
            runOnUiThread {
                if (working == null) {
                    Log.w(TAG, "预检失败[${line.name}]")
                    setStatus(line.name, "流不可播")
                    b.avtpLoading.visibility = View.VISIBLE
                    b.avtpStatus.text = "${line.name}：流拿回来了但拉不到播放列表"
                    if (autoSwitchOn) nextUntriedLine("预检失败")
                } else {
                    Log.i(TAG, "预检通过[${line.name}] referer='$working'")
                    setStatus(line.name, "可用")
                    gsyPlay(stream, working)
                }
            }
        }.start()
    }

    /** 各线路对 Referer 的要求不一样，逐个试，谁能拉到 #EXTM3U 就用谁 */
    private fun pickReferer(stream: String, line: Line): String? {
        val cands = LinkedHashSet<String>()
        cands.add(line.referer)
        cands.add("")
        platformOrigin()?.let { cands.add(it) }
        cands.add("https://jx.202617.xyz/")
        for (r in cands) {
            val ok = loadsPlaylist(stream, r)
            Log.i(TAG, "  预检 referer='$r' -> $ok")
            if (ok) return r
        }
        return null
    }

    private fun loadsPlaylist(url: String, referer: String): Boolean = try {
        val rb = Request.Builder().url(url).header("User-Agent", Http.UA)
        if (referer.isNotBlank()) rb.header("Referer", referer)
        aliveClient.newCall(rb.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string().orEmpty().trimStart().startsWith("#EXTM3U")
            else false
        }
    } catch (e: Exception) {
        false
    }

    private fun platformOrigin(): String? = runCatching {
        val h = java.net.URI(videoUrl).host ?: return@runCatching null
        "https://$h/"
    }.getOrNull()

    private fun onResolveFail(reason: String, line: Line) {
        setStatus(line.name, "失效")
        b.avtpLoading.visibility = View.VISIBLE
        b.avtpStatus.text = "${line.name}：$reason"
        probe?.destroy()
        probe = null
        if (autoSwitchOn) nextUntriedLine("探针失败")
    }

    private fun nextUntriedLine(why: String) {
        Log.w(TAG, "换线路[$why] 已试=$tried")
        val next = lines.indices.firstOrNull { lines[it].name !in tried }
        if (next == null) {
            b.avtpLoading.visibility = View.VISIBLE
            b.avtpStatus.text = "所有线路都失败了（共 ${lines.size} 条）"
            return
        }
        lineIndex = next
        renderPanel()
        startResolve("换线:$why")
    }

    // ── 选集 ────────────────────────────────────────────────────────────

    private fun loadEpisodes() {
        if (albumId.isBlank()) { b.avtpBtnEps.visibility = View.GONE; return }
        Log.i(TAG, "拉选集 albumId=$albumId")
        Thread {
            val eps = PlatformApi.iqiyiEpisodes(albumId)
            runOnUiThread {
                episodes = eps
                Log.i(TAG, "选集 ${eps.size} 集")
                if (eps.isEmpty()) {
                    b.avtpBtnEps.visibility = View.GONE
                } else {
                    b.avtpBtnEps.visibility = View.VISIBLE
                    // 当前播放的是第几集
                    epIndex = eps.indexOfFirst { it.url == videoUrl }
                        .let { if (it >= 0) it else eps.indexOfFirst { sameTv(it.url, videoUrl) } }
                    paintEps()
                }
            }
        }.start()
    }

    /** 播放页 URL 可能带参数，比对 tvId 部分 */
    private fun sameTv(a: String, b: String): Boolean {
        val ra = Regex("""/v_([0-9a-zA-Z]+)\.html""").find(a)?.groupValues?.get(1) ?: return false
        val rb = Regex("""/v_([0-9a-zA-Z]+)\.html""").find(b)?.groupValues?.get(1) ?: return false
        return ra == rb
    }

    private fun gotoEpisode(i: Int) {
        if (episodes.isEmpty()) return
        if (i < 0 || i >= episodes.size) { toast("没有更多了"); return }
        val ep = episodes[i]
        Log.i(TAG, "切到第${ep.order}集 ${ep.url}")
        epIndex = i
        videoUrl = ep.url
        resumeMs = 0
        tried.clear(); retried.clear()
        b.avtpLinePanel.visibility = View.GONE; linePanelOpen = false
        b.avtpEpsPanel.visibility = View.GONE
        paintEps()
        startResolve("选集")
    }

    private fun toggleEpsPanel() {
        if (episodes.isEmpty()) { toast("没有拿到选集列表"); return }
        val show = b.avtpEpsPanel.visibility != View.VISIBLE
        b.avtpEpsPanel.visibility = if (show) View.VISIBLE else View.GONE
        b.avtpLinePanel.visibility = View.GONE; linePanelOpen = false
    }

    private fun paintEps() {
        b.avtpBtnPrev.visibility = if (episodes.isEmpty()) View.GONE else View.VISIBLE
        b.avtpBtnNext.visibility = if (episodes.isEmpty()) View.GONE else View.VISIBLE
        b.avtpBtnPrev.alpha = if (epIndex > 0) 1f else 0.4f
        b.avtpBtnNext.alpha = if (epIndex in 0 until episodes.size - 1) 1f else 0.4f
        b.avtpEpsTitle.text = "选集 · 共 ${episodes.size} 集" +
            if (epIndex >= 0) " · 正在播放第 ${episodes[epIndex].order} 集" else ""

        b.avtpEpsList.removeAllViews()
        val perRow = 5
        var row: LinearLayout? = null
        episodes.forEachIndexed { i, ep ->
            if (i % perRow == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                b.avtpEpsList.addView(row)
            }
            val cur = i == epIndex
            val btn = Button(this).apply {
                text = ep.order.toString()
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
                setBackgroundColor(if (cur) 0xFF334A6B.toInt() else 0xFF1F2026.toInt())
                setTextColor(if (cur) 0xFF4DA3FF.toInt() else 0xFFD8D8DC.toInt())
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { gotoEpisode(i) }
            }
            row?.addView(btn)
        }
    }

    // ── 线路面板 ────────────────────────────────────────────────────────

    private fun toggleLinePanel() {
        linePanelOpen = !linePanelOpen
        b.avtpEpsPanel.visibility = View.GONE
        renderPanel()
    }

    private fun switchLine(i: Int) {
        if (i == lineIndex) { toggleLinePanel(); return }
        resumeMs = currentPosition()
        lineIndex = i
        linePanelOpen = false
        tried.clear(); retried.clear()
        renderPanel()
        startResolve("手选线路")
    }

    private fun currentPosition(): Long = runCatching { b.avtpPlayer.currentPositionWhenPlaying }.getOrDefault(resumeMs)

    private fun renderPanel() {
        b.avtpLineList.removeAllViews()
        lines.forEachIndexed { i, line ->
            val st = lineStatus[line.name].orEmpty()
            val active = i == lineIndex
            val tv = TextView(this).apply {
                text = buildString {
                    append(if (active) "▶ " else "${i + 1}. ")
                    append(line.name)
                    if (st.isNotEmpty()) append("   · $st")
                }
                setTextColor(if (active) 0xFF4DA3FF.toInt() else 0xFFECECEC.toInt())
                textSize = 13.5f
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { switchLine(i) }
            }
            b.avtpLineList.addView(tv)
        }
        b.avtpLinePanel.visibility = if (linePanelOpen) View.VISIBLE else View.GONE
        b.avtpCurrentLine.text = lines.getOrNull(lineIndex)?.name.orEmpty()
    }

    private fun setStatus(name: String, s: String) {
        lineStatus[name] = s
        renderPanel()
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    override fun onStop() {
        super.onStop()
        resumeMs = currentPosition()
        runCatching { b.avtpPlayer.onVideoPause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        probe?.destroy(); probe = null
        runCatching { orientationUtils?.releaseListener() }
        runCatching { b.avtpPlayer.release() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LINE_INDEX = "line_index"
        const val EXTRA_ALBUM_ID = "album_id"
        const val TAG = "AvtpPlay"
        private const val TIMEOUT_MS = 40_000L
    }
}
