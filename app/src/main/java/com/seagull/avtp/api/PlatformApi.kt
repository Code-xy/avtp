package com.seagull.avtp.api

import com.seagull.avtp.parse.Http
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 统一条目模型 —— 三个平台的抓取结果都归一到这里，UI 层只认它 */
data class Item(
    val title: String,
    val cover: String,
    val pageUrl: String,
    /** 有专辑 id 才能拉选集列表（爱奇艺） */
    val albumId: String = "",
)

/** 一集 */
data class Episode(
    val order: Int,
    val name: String,
    val url: String,
)

/** 筛选组（频道 / 类型 / 地区 / 年份） */
data class FilterGroup(
    val name: String,
    val options: List<Pair<String, String>>, // 显示名 -> 取值
)

/**
 * 三家官网的**原生接口**抓取。全部返回 JSON，用 org.json 解析，不依赖 WebView。
 *
 * 实测时间 2026-10-13：
 *  - 爱奇艺 搜索 / 频道筛选  ✅
 *  - 优酷   搜索（自带筛选）  ✅
 *  - 腾讯   详情 float_vinfo2 ✅ ；搜索/筛选 待接（页面是 SPA，接口带签名）
 */
object PlatformApi {

    private fun get(url: String, referer: String): String? = runCatching {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.UA)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ── 爱奇艺 ─────────────────────────────────────────────────────────

    /** 频道 id：1=电影 2=电视剧 4=动漫 6=综艺 3=纪录片 */
    val IQIYI_CHANNELS = listOf(
        "电影" to "1", "电视剧" to "2", "综艺" to "6", "动漫" to "4", "纪录片" to "3",
    )

    /**
     * 爱奇艺频道筛选 —— pcw-api 直接给 playUrl（就是 v_xxx.html 播放页），最适合直接解析
     * https://pcw-api.iqiyi.com/search/recommend/list?channel_id=2&data_type=1&mode=24&page_id=1&ret_num=24
     */
    fun iqiyiChannel(channelId: String, page: Int = 1): List<Item> {
        val u = "https://pcw-api.iqiyi.com/search/recommend/list" +
            "?channel_id=$channelId&data_type=1&mode=24&page_id=$page&ret_num=24"
        val body = get(u, "https://www.iqiyi.com/") ?: return emptyList()
        val list = runCatching {
            JSONObject(body).getJSONObject("data").getJSONArray("list")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<Item>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val title = o.optString("name").ifBlank { o.optString("title") }
            val url = o.optString("playUrl")
            if (title.isBlank() || url.isBlank()) continue
            out.add(Item(title, o.optString("imageUrl"), url, o.optString("albumId")))
        }
        return out
    }

    /**
     * 爱奇艺搜索
     * https://search.video.iqiyi.com/o?if=html5&key=三体&pageNum=1&pageSize=20
     * docinfos[].albumDocInfo -> albumTitle / albumVImage / albumLink
     */
    fun iqiyiSearch(keyword: String, page: Int = 1): List<Item> {
        val u = "https://search.video.iqiyi.com/o?if=html5&key=${enc(keyword)}" +
            "&pageNum=$page&pageSize=20"
        val body = get(u, "https://www.iqiyi.com/") ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONArray("docinfos")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val doc = arr.optJSONObject(i) ?: continue
            // 优先专辑信息；拿不到就退回单视频
            val a = doc.optJSONObject("albumDocInfo")
            if (a != null) {
                val title = a.optString("albumTitle")
                var url = a.optString("albumLink")
                if (url.isBlank()) {
                    val vid = firstVideoId(a)
                    if (vid.isNotBlank()) url = "https://www.iqiyi.com/v_$vid.html"
                }
                if (title.isNotBlank() && url.isNotBlank()) {
                    out.add(Item(title, firstImage(a.optString("albumVImage")), url, a.optString("albumId")))
                    continue
                }
            }
            val v = doc.optJSONObject("videoDocInfo") ?: continue
            val vt = v.optString("title")
            val vu = v.optString("pageUrl")
            if (vt.isNotBlank() && vu.isNotBlank()) {
                out.add(Item(vt, firstImage(v.optString("img")), vu))
            }
        }
        return out
    }

    private fun firstVideoId(album: JSONObject): String {
        val vs = album.optJSONArray("videoinfos") ?: return ""
        for (i in 0 until vs.length()) {
            val o = vs.optJSONObject(i) ?: continue
            val id = o.optString("videoId").ifBlank { o.optString("tvId") }
            if (id.isNotBlank()) return id
        }
        return ""
    }

    /** 爱奇艺的图字段是逗号分隔的多尺寸，取第一个 */
    private fun firstImage(raw: String?): String =
        raw.orEmpty().split(",").firstOrNull()?.trim().orEmpty()

    // ── 优酷 ───────────────────────────────────────────────────────────

    /**
     * 优酷搜索 —— search.youku.com/api/search 直接吐 JSON，且 pageData.filter 自带筛选项
     * 结果藏在 pageComponentList[].componentMap[*].data[]，条目有 videoId
     */
    fun youkuSearch(keyword: String, page: Int = 1): List<Item> {
        val u = "https://search.youku.com/api/search?keyword=${enc(keyword)}&page=$page"
        val body = get(u, "https://www.youku.com/") ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (root.optString("message") != "success") return emptyList()

        val out = ArrayList<Item>()
        val comps = root.optJSONArray("pageComponentList") ?: return emptyList()
        for (i in 0 until comps.length()) {
            val map = comps.optJSONObject(i)?.optJSONObject("componentMap") ?: continue
            val keys = map.keys()
            while (keys.hasNext()) {
                val data = map.optJSONObject(keys.next())?.optJSONArray("data") ?: continue
                for (j in 0 until data.length()) {
                    val o = data.optJSONObject(j) ?: continue
                    val title = o.optString("title").ifBlank { o.optString("displayName") }
                    val vid = o.optString("videoId")
                    if (title.isBlank() || vid.isBlank() || vid == "null") continue
                    out.add(
                        Item(
                            title = title,
                            cover = youkuCover(o),
                            pageUrl = "https://v.youku.com/v_show/id_$vid.html",
                        )
                    )
                }
            }
        }
        return out.distinctBy { it.pageUrl }
    }

    private fun youkuCover(o: JSONObject): String {
        for (k in listOf("img", "image", "poster", "cover", "thumbnail", "imgUrl")) {
            val v = o.optString(k)
            if (v.startsWith("http")) return v
        }
        // 有些藏在 action/extra 里的 JSON 串
        val extra = o.optString("extra")
        if (extra.startsWith("{")) {
            runCatching { JSONObject(extra) }.getOrNull()?.let { e ->
                for (k in listOf("img", "poster", "cover")) {
                    val v = e.optString(k)
                    if (v.startsWith("http")) return v
                }
            }
        }
        return ""
    }

    // ── 选集 ───────────────────────────────────────────────────────────

    /**
     * 爱奇艺选集
     * https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=<albumId>&page=1&size=100
     * → data.epsodelist[]：order / name / playUrl(v_xxx.html) / vid / duration
     */
    fun iqiyiEpisodes(albumId: String): List<Episode> {
        if (albumId.isBlank() || albumId == "0") return emptyList()
        val u = "https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=$albumId&page=1&size=100"
        val body = get(u, "https://www.iqiyi.com/") ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("data").getJSONArray("epsodelist")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<Episode>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("playUrl")
            if (url.isBlank()) continue
            val order = o.optInt("order", i + 1)
            val name = o.optString("shortTitle").ifBlank {
                o.optString("name").ifBlank { "第${order}集" }
            }
            out.add(Episode(order, name, url))
        }
        return out.sortedBy { it.order }
    }

    /** 腾讯选集：float_vinfo2 的 c.video_ids[] 就是分集 vid 序列 */
    fun tencentEpisodes(cid: String): List<Episode> {
        if (cid.isBlank()) return emptyList()
        val body = get("https://node.video.qq.com/x/api/float_vinfo2?cid=$cid", "https://v.qq.com/")
            ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("c").getJSONArray("video_ids")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val vid = arr.optString(i)
            if (vid.isBlank()) continue
            out.add(Episode(i + 1, "第${i + 1}集", "https://v.qq.com/x/page/$vid.html"))
        }
        return out
    }

    // ── 腾讯（待接）────────────────────────────────────────────────────

    /**
     * 腾讯详情已通：node.video.qq.com/x/api/float_vinfo2?cid=xxx
     * 搜索/筛选还没找到无签名的公开接口（页面是 SPA，走 pbaccess 带签）。
     * 暂时用图文提示，后续补。
     */
    fun tencentSearch(keyword: String, page: Int = 1): List<Item> = emptyList()

    fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").trim()
}
