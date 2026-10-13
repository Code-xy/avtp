package com.seagull.avtp.parse

import java.net.URI
import java.net.URLEncoder

enum class Platform(val label: String) {
    IQIYI("爱奇艺"), YOUKU("优酷"), QQ("腾讯视频"), UNKNOWN("未知");

    companion object {
        fun of(host: String): Platform {
            val h = host.lowercase()
            return when {
                "iqiyi" in h || "iq.com" in h -> IQIYI
                "youku" in h || "tudou" in h -> YOUKU
                "qq.com" in h -> QQ
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 解析线路。api 是前缀，实际请求 = api + urlEncode(视频页URL)。
 * referer 用于拉流时的防盗链 —— 不带多数线路直接 403。
 */
data class Line(
    val name: String,
    val api: String,
    val note: String = "",
    val enabled: Boolean = true,
) {
    val referer: String get() = runCatching {
        val u = URI(api)
        "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}/"
    }.getOrDefault(api.substringBefore("?", api))

    fun buildUrl(videoUrl: String): String =
        api + URLEncoder.encode(videoUrl, "UTF-8")
}

object LinePool {

    /** 实测状态见 RECIPE_qige.md / lineprobe 报告 */
    val DEFAULT: List<Line> = listOf(
        Line("七哥解析", "https://jx.202617.xyz/tv.php?url=", "已实测可用 · 需点播放"),
        Line("无损云", "https://free.maccms.xyz/?url=", "反调试+授权校验"),
        Line("4-TXNQ", "https://bfq.txnp.cn/player?url=", "crypto-js 参数加密"),
        Line("麒麟解析", "https://yparse.ik9.cc/index.php?url=", "待验"),
        Line("fongmi", "https://json.fongmi.cc/web?url=", "待验"),
        Line("冰豆", "https://bd.jx.cn/?url=", "疑似失效"),
    )
}

object UrlNormalizer {

    private val rxUrl = Regex("""https?://[\w\-./?%&=:#@+~]+""")

    private val TRACKING = setOf(
        "share_token", "from", "from_source", "vfm", "ptag", "ptag2", "spm",
        "cvid", "share_channel", "sharetype", "share_medium", "share_plat",
        "share_tag", "s", "shareId", "source", "fc", "mod",
    )

    /** 从任意分享文本里抠出第一个链接 */
    fun extract(text: String): String? = rxUrl.find(text)?.value

    /** 清洗 + 识别平台；识别不出返回 null */
    fun normalize(text: String): Pair<Platform, String>? {
        val raw = extract(text.trim()) ?: return null
        val u = runCatching { URI(raw) }.getOrNull() ?: return null
        val platform = Platform.of(u.host ?: "")
        if (platform == Platform.UNKNOWN) return null

        val cleaned = runCatching {
            val q = u.rawQuery
            if (q.isNullOrBlank()) raw else {
                val kept = q.split("&").filter { kv ->
                    kv.substringBefore("=").isNotBlank() && kv.substringBefore("=") !in TRACKING
                }
                URI(u.scheme, u.userInfo, u.host, u.port, u.path, kept.joinToString("&").ifBlank { null }, null)
                    .toString()
            }
        }.getOrDefault(raw)

        return platform to cleaned
    }
}
