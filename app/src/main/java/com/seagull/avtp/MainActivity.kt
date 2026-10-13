package com.seagull.avtp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seagull.avtp.browser.BrowserActivity
import com.seagull.avtp.databinding.ActivityMainBinding
import com.seagull.avtp.parse.LinePool
import com.seagull.avtp.parse.Platform
import com.seagull.avtp.parse.UrlNormalizer
import com.seagull.avtp.player.PlayerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var platform: Platform = Platform.UNKNOWN
    private var cleanUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) = detect(s?.toString().orEmpty())
        })

        b.btnPaste.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) toast("剪贴板是空的") else {
                b.urlInput.setText(text)
                b.urlInput.setSelection(b.urlInput.text.length)
            }
        }

        b.btnGo.setOnClickListener { go(0) }

        b.btnIqiyi.setOnClickListener { openOfficial("iqiyi") }
        b.btnYouku.setOnClickListener { openOfficial("youku") }
        b.btnQq.setOnClickListener { openOfficial("qq") }

        buildLineList()
        handleShare(intent)
    }

    private fun openOfficial(platform: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PLATFORM, platform)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) {
            b.urlInput.setText(text)
            b.urlInput.setSelection(b.urlInput.text.length)
        }
    }

    private fun detect(raw: String) {
        val r = UrlNormalizer.normalize(raw)
        if (r == null) {
            platform = Platform.UNKNOWN
            cleanUrl = ""
            b.detectText.text = if (raw.isBlank()) "支持：爱奇艺 / 优酷 / 腾讯视频 的剧集页面链接"
                               else "没识别出平台 —— 粘贴爱优腾的播放页链接"
            b.btnGo.isEnabled = false
        } else {
            platform = r.first
            cleanUrl = r.second
            b.detectText.text = "已识别：${r.first.label}"
            b.btnGo.isEnabled = true
        }
    }

    private fun buildLineList() {
        b.lineList.removeAllViews()
        LinePool.DEFAULT.forEachIndexed { i, line ->
            val tv = TextView(this).apply {
                text = "${i + 1}. ${line.name}${if (line.note.isBlank()) "" else "   · ${line.note}"}"
                textSize = 13.5f
                setTextColor(0xFFECECEC.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setOnClickListener { go(i) }
            }
            b.lineList.addView(tv)
        }
    }

    private fun go(index: Int) {
        if (cleanUrl.isBlank()) {
            toast("先粘贴一个爱优腾链接")
            return
        }
        b.linePanel.visibility = View.VISIBLE
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO_URL, cleanUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, "${platform.label} · 解析")
                .putExtra(PlayerActivity.EXTRA_LINE_INDEX, index)
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
