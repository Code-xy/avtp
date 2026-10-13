package com.seagull.avtp.browser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.seagull.avtp.R
import com.seagull.avtp.api.Item
import com.seagull.avtp.api.PlatformApi
import com.seagull.avtp.databinding.ActivityBrowseBinding
import com.seagull.avtp.parse.Platform
import com.seagull.avtp.parse.UrlNormalizer
import com.seagull.avtp.player.PlayerActivity
import com.seagull.avtp.ui.VideoAdapter

/**
 * 首页：原生筛选 + 搜索（不加载官网页面）
 *
 *   爱奇艺 频道 pcw-api.iqiyi.com/search/recommend/list   / 搜索 search.video.iqiyi.com/o
 *   优酷   搜索 search.youku.com/api/search（自带 类型/时长/清晰度/排序 筛选；接口不返回封面）
 *   腾讯   频道 v.qq.com/channel/<slug> 内联脚本正则兜底 / 搜索接口带签名，暂缺
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowseBinding
    private var platform = Platform.IQIYI
    private var channelId = "1"
    private var channelSlug = "tv"
    private var channelWord = "电视剧"

    private var page = 1
    private var loadingMore = false
    private var noMore = false
    private var keyword = ""          // 非空 = 搜索模式

    private val adapter = VideoAdapter { openPlayer(it) }

    private val iqiyiChannels = listOf("电影" to "1", "电视剧" to "2", "综艺" to "6", "动漫" to "4", "纪录片" to "3")
    private val youkuWords = listOf("电视剧", "电影", "综艺", "动漫", "纪录片")
    private val qqChannels = listOf(
        "电视剧" to "tv", "电影" to "movie", "综艺" to "variety",
        "动漫" to "cartoon", "纪录片" to "doco",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.avtpGrid.layoutManager = GridLayoutManager(this, 3)
        b.avtpGrid.adapter = adapter
        b.avtpGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingMore || noMore) return
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.size() - 6) loadPage(reset = false)
            }
        })

        b.avtpTabIqiyi.setOnClickListener { switchPlatform(Platform.IQIYI) }
        b.avtpTabYouku.setOnClickListener { switchPlatform(Platform.YOUKU) }
        b.avtpTabQq.setOnClickListener { switchPlatform(Platform.QQ) }

        b.avtpBtnSearch.setOnClickListener { doSearch() }
        b.avtpSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        b.avtpBtnPaste.setOnClickListener { showPasteDialog(null) }

        val start = when (intent.getStringExtra(EXTRA_PLATFORM)) {
            "youku" -> Platform.YOUKU
            "qq" -> Platform.QQ
            else -> Platform.IQIYI
        }
        switchPlatform(start)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            showPasteDialog(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    // ── 平台 / 筛选 ─────────────────────────────────────────────────────

    private fun switchPlatform(p: Platform) {
        platform = p
        keyword = ""
        b.avtpSearch.setText("")
        b.avtpTabIqiyi.isSelected = p == Platform.IQIYI
        b.avtpTabYouku.isSelected = p == Platform.YOUKU
        b.avtpTabQq.isSelected = p == Platform.QQ

        val list: List<Pair<String, String>> = when (p) {
            Platform.YOUKU -> youkuWords.map { it to it }
            Platform.QQ -> qqChannels
            else -> iqiyiChannels
        }
        buildChips(list)
        when (p) {
            Platform.YOUKU -> { channelWord = "电视剧"; b.avtpSearch.hint = "搜剧名（频道词可作筛选）" }
            Platform.QQ -> { channelSlug = "tv"; b.avtpSearch.hint = "腾讯搜索暂缺，用频道浏览" }
            else -> { channelId = "1"; b.avtpSearch.hint = "搜剧名 / 演员" }
        }
        loadPage(reset = true)
    }

    private fun buildChips(list: List<Pair<String, String>>) {
        b.avtpChips.removeAllViews()
        val btns = ArrayList<Button>()
        list.forEachIndexed { i, (label, value) ->
            val btn = layoutInflater.inflate(R.layout.item_chip, b.avtpChips, false) as Button
            btn.text = label
            btn.isSelected = i == 0
            btn.setOnClickListener {
                btns.forEachIndexed { j, x -> x.isSelected = (j == i) }
                keyword = ""
                when (platform) {
                    Platform.YOUKU -> channelWord = value
                    Platform.QQ -> channelSlug = value
                    else -> channelId = value
                }
                loadPage(reset = true)
            }
            btns.add(btn)
            b.avtpChips.addView(btn)
        }
    }

    private fun doSearch() {
        val kw = b.avtpSearch.text.toString().trim()
        if (kw.isBlank()) { toast("先输个剧名"); return }
        keyword = kw
        loadPage(reset = true)
    }

    // ── 数据 ────────────────────────────────────────────────────────────

    private fun loadPage(reset: Boolean) {
        if (reset) { page = 1; noMore = false; adapter.clear() } else {
            if (loadingMore || noMore) return
            loadingMore = true
        }
        val p = page
        busy(if (reset) "加载中…" else "加载第 $p 页…")

        Thread {
            val list = fetch(p)
            runOnUiThread {
                loadingMore = false
                b.avtpLoading.visibility = View.GONE
                if (list.isEmpty()) {
                    if (reset) { noMore = true; adapter.submit(emptyList()) } else noMore = true
                    b.avtpStatus.text = emptyMsg()
                    return@runOnUiThread
                }
                if (reset) adapter.submit(list) else adapter.append(list)
                if (list.size < PAGE_SIZE) noMore = true
                b.avtpStatus.text = "${platform.label} · ${if (keyword.isNotBlank()) "「$keyword」" else channelLabel()} · ${adapter.size()} 条${if (noMore) "" else " · 滑到底加载更多"}"
            }
        }.start()
    }

    private fun fetch(p: Int): List<Item> = when (platform) {
        Platform.IQIYI -> if (keyword.isNotBlank()) PlatformApi.iqiyiSearch(keyword, p)
                          else PlatformApi.iqiyiChannel(channelId, p)
        Platform.YOUKU -> PlatformApi.youkuSearch(if (keyword.isNotBlank()) keyword else channelWord, page = p)
        Platform.QQ -> if (p == 1) PlatformApi.tencentChannel(channelSlug) else emptyList()
        else -> emptyList()
    }

    private fun channelLabel(): String = when (platform) {
        Platform.YOUKU -> channelWord
        Platform.QQ -> qqChannels.firstOrNull { it.second == channelSlug }?.first ?: "频道"
        else -> iqiyiChannels.firstOrNull { it.second == channelId }?.first ?: "频道"
    }

    private fun emptyMsg(): String = when {
        platform == Platform.QQ && keyword.isNotBlank() -> "腾讯搜索接口带签名，还没接上（先用上方频道浏览）"
        platform == Platform.QQ -> "腾讯「${channelLabel()}」频道页没解析到（正则兜底，可能失效）"
        keyword.isNotBlank() -> "「$keyword」没搜到结果"
        else -> "没拿到数据（接口可能变了）"
    }

    private fun busy(msg: String) {
        b.avtpLoading.visibility = View.VISIBLE
        b.avtpStatus.text = msg
    }

    // ── 粘贴解析 ────────────────────────────────────────────────────────

    private fun showPasteDialog(preset: String?) {
        val input = EditText(this).apply {
            hint = "粘贴爱奇艺 / 优酷 / 腾讯 的剧集链接"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setTextColor(Color.parseColor("#F2F3F5"))
            setHintTextColor(Color.parseColor("#6A7078"))
            background = resources.getDrawable(R.drawable.bg_input, null)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textSize = 13f
            if (!preset.isNullOrBlank()) setText(preset)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("链接解析")
            .setView(wrap)
            .setPositiveButton("解析播放") { _, _ ->
                val n = UrlNormalizer.normalize(input.text.toString())
                if (n == null) toast("没识别出爱优腾链接") else openPlayer(Item("解析", "", n.second))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openPlayer(item: Item) {
        if (!item.pageUrl.startsWith("http")) { toast("这条没有播放页链接"); return }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, item.pageUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                .putExtra(PlayerActivity.EXTRA_ALBUM_ID, item.albumId)
                .putExtra(PlayerActivity.EXTRA_LINE_INDEX, 0)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PLATFORM = "platform"
        private const val PAGE_SIZE = 24
    }
}
