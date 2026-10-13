package com.seagull.avtp.browser

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.seagull.avtp.api.Item
import com.seagull.avtp.api.PlatformApi
import com.seagull.avtp.databinding.ActivityBrowseBinding
import com.seagull.avtp.parse.Platform
import com.seagull.avtp.player.PlayerActivity
import com.seagull.avtp.ui.VideoAdapter

/**
 * 筛选 + 搜索页（纯原生，不加载官网页面）
 *
 * 数据来自各平台自己的 JSON 接口：
 *   爱奇艺 频道 pcw-api.iqiyi.com/search/recommend/list  （直接给 v_xxx.html 播放页）
 *   爱奇艺 搜索 search.video.iqiyi.com/o
 *   优酷   搜索 search.youku.com/api/search
 *   腾讯   待接
 *
 * 点条目 → 直接把该剧的播放页 URL 交给解析线路 → 原生播放。
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowseBinding
    private var platform = Platform.IQIYI
    private var channel = "1"                 // 爱奇艺频道 id
    private var page = 1
    private val adapter = VideoAdapter { openPlayer(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.grid.layoutManager = GridLayoutManager(this, 3)
        b.grid.adapter = adapter

        b.tabIqiyi.setOnClickListener { switchPlatform(Platform.IQIYI) }
        b.tabYouku.setOnClickListener { switchPlatform(Platform.YOUKU) }
        b.tabQq.setOnClickListener { switchPlatform(Platform.QQ) }

        b.btnSearch.setOnClickListener { doSearch() }
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        val start = when (intent.getStringExtra(EXTRA_PLATFORM)) {
            "youku" -> Platform.YOUKU
            "qq" -> Platform.QQ
            else -> Platform.IQIYI
        }
        switchPlatform(start)
    }

    private fun switchPlatform(p: Platform) {
        platform = p
        page = 1
        adapter.clear()
        b.chips.removeAllViews()
        b.searchInput.setText("")
        highlightTab()
        when (p) {
            Platform.IQIYI -> {
                b.statusText.text = "爱奇艺 · 选频道或直接搜"
                PlatformApi.IQIYI_CHANNELS.forEach { (name, id) ->
                    b.chips.addView(chip(name, id == channel) { channel = id; loadChannel() })
                }
                loadChannel()
            }
            Platform.YOUKU -> {
                b.statusText.text = "优酷 · 输入剧名搜索（接口自带时长/类型/排序筛选）"
                b.chips.addView(chip("点这里搜「三体」试试底噪", false) {
                    b.searchInput.setText("三体"); doSearch()
                })
            }
            else -> {
                b.statusText.text = "腾讯视频：搜索接口带签名，还没接上（详情接口已通）"
                b.chips.addView(chip("暂不可用", true) {})
            }
        }
    }

    private fun highlightTab() {
        val on = Color.parseColor("#4DA3FF")
        val off = Color.parseColor("#2A2B31")
        b.tabIqiyi.setBackgroundColor(if (platform == Platform.IQIYI) on else off)
        b.tabYouku.setBackgroundColor(if (platform == Platform.YOUKU) on else off)
        b.tabQq.setBackgroundColor(if (platform == Platform.QQ) on else off)
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = text
        btn.textSize = 12.5f
        btn.isAllCaps = false
        btn.setPadding(dp(16), dp(4), dp(16), dp(4))
        btn.minWidth = 0
        btn.minimumWidth = 0
        btn.setBackgroundColor(if (selected) Color.parseColor("#334A6B") else Color.parseColor("#1A1B20"))
        btn.setTextColor(if (selected) Color.parseColor("#4DA3FF") else Color.parseColor("#C9C9CE"))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
        )
        lp.rightMargin = dp(8)
        btn.layoutParams = lp
        btn.setOnClickListener {
            onClick()
            // 重新渲染 chips 选中态
            val idx = b.chips.indexOfChild(btn)
            for (i in 0 until b.chips.childCount) {
                val c = b.chips.getChildAt(i) as Button
                val sel = i == idx
                c.setBackgroundColor(if (sel) Color.parseColor("#334A6B") else Color.parseColor("#1A1B20"))
                c.setTextColor(if (sel) Color.parseColor("#4DA3FF") else Color.parseColor("#C9C9CE"))
            }
        }
        return btn
    }

    // ── 数据 ────────────────────────────────────────────────────────────

    private fun loadChannel() {
        busy(true, "加载中…")
        Thread {
            val list = PlatformApi.iqiyiChannel(channel, 1)
            runOnUiThread {
                busy(false, if (list.isEmpty()) "没拿到数据（接口可能变了）" else "共 ${list.size} 条")
                adapter.submit(list)
            }
        }.start()
    }

    private fun doSearch() {
        val kw = b.searchInput.text.toString().trim()
        if (kw.isBlank()) { toast("先输个剧名"); return }
        busy(true, "搜索「$kw」…")
        Thread {
            val list = when (platform) {
                Platform.IQIYI -> PlatformApi.iqiyiSearch(kw, 1)
                Platform.YOUKU -> PlatformApi.youkuSearch(kw, 1)
                else -> emptyList()
            }
            runOnUiThread {
                busy(false, if (list.isEmpty()) "「$kw」没搜到结果" else "「$kw」共 ${list.size} 条")
                adapter.submit(list)
            }
        }.start()
    }

    private fun busy(on: Boolean, msg: String? = null) {
        b.loading.visibility = if (on) View.VISIBLE else View.GONE
        msg?.let { b.statusText.text = it }
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
    }
}
