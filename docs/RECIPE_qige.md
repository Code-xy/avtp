# 七哥解析（jx.202617.xyz）全链路 —— 已实测验证 2026-10-13

## 验证结论

代理流地址返回 `Content-Type: application/vnd.apple.mpegurl`，body `#EXTM3U` 开头，90478 字节。
**真流，可播。** 线路可用，不是死的。

## 完整链路

```
[1] GET https://jx.202617.xyz/tv.php?url=<爱优腾URL>
      ↓ 首次访问返回签名挑战（防不执行JS的爬虫）
      <script>location.href = origin + "/tv.php?url=..&redirectjs_sign=MD5&redirectjs_time=<unix>"</script>
      ↓ 签名不用逆 —— 服务端自己吐出来的，原样回填重放即可
[2] 拿到 12286B 播放器页 → ArtPlayer + Hls.js（player.js 209KB, jsjiami.com.v7 混淆）
      ↓ 必须用户点击「立即播放」(#btn) 才触发取流
[3] POST https://jx.202617.xyz/apiai.php
      Content-Type: application/x-www-form-urlencoded
      X-Requested-With: XMLHttpRequest
      Origin/Referer: https://jx.202617.xyz/
      body: url=<爱优腾URL>&api_key=player
      → 536B 密文（AES，JS 端解密）      ★ 沙箱直连可调通，无需浏览器
[4] 解密后得到代理播放列表：
      https://114.66.27.228:55789/global?proxy=<b64>&s=<hex>&src=ptqy
        &t=<时间令牌>&type=playlist&u=<b64>&ut=sid
[5] GET 该 URL  → 200 application/vnd.apple.mpegurl
      #EXTM3U / #EXT-X-TARGETDURATION:10 / #EXTINF:8.000000,...
      分片：https://anycast.video.qq.com.<punycode>/global?id=..&s=..&type=hls&up=..
```

## 给 App 的三条硬要求

1. **必须透传 `Referer: https://jx.202617.xyz/`** 给 ExoPlayer。
   实测：不带 Referer / 不在页面上下文 → **403**（会话绑定，`ut=sid`）。
2. **URL 不能缓存**。`t=` 是时间令牌，每隔几十秒变一次，且会话绑定。
   每次播放都要重新解析，不能存历史直链。
3. **WebView 与播放器要共享 CookieJar**。流是会话绑定的，嗅探到的 URL
   必须带上 WebView 会话的 cookie 才能播。

## 两种取流实现（按优先级）

### A. WebView 嗅探（推荐，零逆向）
```
WebView.loadUrl("https://jx.202617.xyz/tv.php?url=" + enc(videoUrl))
  → shouldInterceptRequest 抓 url 含 "type=playlist" 的请求
  → 拿到 URL → 销毁 WebView → ExoPlayer.setMediaItem(url, Referer=jx.202617.xyz)
    + 共享 CookieJar
```
注意：页面需要一次点击才取流，或用 JS 注入模拟 `document.querySelector('#btn').click()`。

### B. 直连 API（更快，但要解 AES）
```
POST /apiai.php  {url, api_key:player}  → 536B AES 密文
需要从 player.js 拿 AES key/iv（可 CDP hook 解密函数拿到，不必读混淆）
```

## 实测抓包要点（可复现）

- MCP: `network attach_cdp` → `browser open` → `dom click #btn` → `network cdp_requests urlFilter=55789`
- `network get_response_body requestId=<id>` 取到完整 m3u8
- 必须 `browser set_stealth` 注入反调试对抗层，否则 jsjiami 自保护会干扰

## 其余线路状态

| 线路 | 状态 |
|---|---|
| 七哥 jx.202617.xyz | ✅ 已打通（本文件） |
| 无损云 free.maccms.xyz | 麒麟播放器，含反调试+授权校验，待攻 |
| 4-TXNQ bfq.txnp.cn | crypto-js 参数加密，待攻 |
| 5-fongmi json.fongmi.cc | 403，待换 Referer/Token |
| 冰豆 / 7-HLS / PlayerJY / 麒麟 | 死 |
