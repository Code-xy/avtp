# Avtp —— 爱优腾解析播放

原生解析显示爱奇艺 / 优酷 / 腾讯视频，右下角对接解析线路。

## 架构（关键：WebView 只当探针，不当播放器）

```
输入层  粘贴/分享 爱优腾链接 → UrlNormalizer 识别平台 + 清洗跟踪参数
取流层  WebViewProbe（1x1 离屏）
          · shouldInterceptRequest 网络层嗅探 type=playlist / .m3u8
          · 自动点播放按钮（#btn / 含"播放"字样的 button）
          · 嗅到流 → 立刻回调 → 销毁 WebView
播放层  ExoPlayer(media3) + OkHttpDataSource
          · 透传 Referer（不带 403）
          · CookieJar 桥接 WebView 的 CookieManager（流是会话绑定的）
线路层  右下角浮层，切换时 seekTo 继承播放进度
```

### 为什么必须用 WebView

解析线路的 m3u8 是**页面 JS 自己算签名 / 解密 / 发请求**拿到的，纯 HTTP 客户端抽不到。
实测 3 条现役线路全部带防护（JSJiami 混淆 / crypto-js 参数加密 / 反调试 + 授权校验）。
只有让真 JS 引擎跑起来，才能从网络层把流地址截下来。

## 已实测的线路

| 线路 | 状态 | 说明 |
|---|---|---|
| 七哥 jx.202617.xyz | ✅ 已打通 | 见 `docs/RECIPE_qige.md` |
| 无损云 free.maccms.xyz | ⚠️ 待攻 | 麒麟播放器，反调试 + 授权校验 |
| 4-TXNQ bfq.txnp.cn | ⚠️ 待攻 | crypto-js 参数加密 |
| 麒麟 / fongmi / 冰豆 | ❓ 待验 | |

## 七哥线路完整链路（已实测验证）

```
GET  jx.202617.xyz/tv.php?url=<爱优腾URL>
     → 首次给签名挑战 redirectjs_sign(32hex) + redirectjs_time(unix)
     → 不用逆，原样回填重放
播放器页  ArtPlayer + Hls.js（player.js 209KB，jsjiami.com.v7 混淆）
     → 必须用户手势点「立即播放」
POST /apiai.php   url=<URL>&api_key=player   X-Requested-With: XMLHttpRequest
     → 536B AES 密文，JS 端解密
得到   https://114.66.27.228:55789/global?proxy=<b64>&s=<hex>&src=ptqy
        &t=<时间令牌>&type=playlist&u=<b64>&ut=sid
GET  →  200 application/vnd.apple.mpegurl   #EXTM3U  90478B   ✅ 真流
```

**三条硬要求**（不满足直接 403 或丢流）：

1. 拉流必须带 `Referer: https://jx.202617.xyz/`
2. 流地址不能缓存（`t=` 时间令牌几十秒过期）
3. WebView 与 ExoPlayer 必须共享 CookieJar

## 编译

云端构建：push 到 main → Actions 出 APK（`.github/workflows/build.yml` → 可复用模板 `android-build.yml`）。

签名固定为仓库内置 `signing/dev-release.jks`（别名 `avtp`，SHA-256 `63:3F:79:76:69:1E:12:D8:1A:2E:CB:0B:2B:48:B5:F7:97:3C:7C:28:9B:4C:62:73:6A:48:05:19:85:72:98:6F`），
保证每个包都能直接覆盖升级。

## 免责

仅供技术研究与自用。请支持正版内容。
