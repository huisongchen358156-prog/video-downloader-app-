package com.noah.videodownloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

// 浏览器模式：像原版视频下载鸟那样，在内置浏览器里打开链接、播放视频，
// 嗅探页面加载的视频文件地址，点下载即可，全程不用登录。
// 原理：页面在 WebView 里像正常用户一样播放，视频地址会出现在网络请求里，
// shouldInterceptRequest 能拿到它；再用 DownloadService 直接下载这个地址。
// WebView 在同一进程里跑，直连开关的绑定对它同样生效。
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var webView: WebView
    private lateinit var detectedBar: TextView
    private lateinit var downloadButton: Button
    private var pageUrl: String = ""
    private var pageTitle: String = "video"
    private val detectedUrls = mutableListOf<String>()
    private val detectedSizes = mutableMapOf<String, Long?>()
    private var dialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        pageUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (pageUrl.isBlank()) {
            finish()
            return
        }
        // 直连开着时先绑直连网络，WebView 在同一进程里，流量也会走直连。
        if (DirectNetwork.isEnabled(this)) {
            Thread { DirectNetwork.ensureBound(applicationContext) }.start()
        }

        webView = findViewById(R.id.browser_webview)
        detectedBar = findViewById(R.id.detected_bar)
        downloadButton = findViewById(R.id.browser_download_button)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    pageTitle = title
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isVideoUrl(url)) {
                    onVideoDetected(url)
                }
                return null
            }
        }
        downloadButton.setOnClickListener { showDetectedDialog() }
        findViewById<Button>(R.id.browser_close_button).setOnClickListener { finish() }

        webView.loadUrl(pageUrl)
    }

    // 判断一个请求地址是不是视频文件：带视频后缀，或抖音播放地址。
    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (".mp4" in lower || ".webm" in lower || ".mov" in lower || ".m3u8" in lower) {
            return true
        }
        if ("aweme/v1/play" in lower) return true
        if ("douyinvod.com" in lower && "video" in lower) return true
        return false
    }

    private fun onVideoDetected(url: String) {
        synchronized(detectedUrls) {
            if (url in detectedUrls) return
            detectedUrls.add(url)
        }
        // 后台拿文件大小（HEAD 请求），对话框里显示用；拿不到就显示未知。
        Thread {
            val size = fetchContentLength(url)
            synchronized(detectedUrls) {
                detectedSizes[url] = size
            }
            runOnUiThread {
                val count = detectedUrls.size
                detectedBar.visibility = View.VISIBLE
                detectedBar.text = "已检测到 $count 个视频，点下面按钮下载"
                downloadButton.isEnabled = true
                downloadButton.text = "下载检测到的视频 ($count)"
                // 第一次检测到就直接弹对话框，像原版那样，不用等用户找按钮。
                if (!dialogShown) {
                    dialogShown = true
                    showDetectedDialog()
                }
            }
        }.start()
    }

    private fun fetchContentLength(url: String): Long? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", pageUrl)
            conn.connect()
            val len = conn.contentLengthLong
            conn.disconnect()
            if (len > 0) len else null
        } catch (e: Exception) {
            null
        }
    }

    private fun showDetectedDialog() {
        val urls = synchronized(detectedUrls) { detectedUrls.toList() }
        if (urls.isEmpty()) {
            Toast.makeText(this, "还没检测到视频，先点播放，等一会儿", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = urls.mapIndexed { index, url ->
            val size = detectedSizes[url]
            val sizeText = if (size != null) " · 约${formatBytes(size)}" else ""
            "视频${index + 1}$sizeText"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("检测到以下视频")
            .setItems(labels) { _, which ->
                startDetectedDownload(urls[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDetectedDownload(mediaUrl: String) {
        val fileName = sanitizeFilename(pageTitle)
        val platform = detectPlatform(pageUrl).label
        DownloadService.startDownload(this, mediaUrl, "direct_url", fileName, platform)
        Toast.makeText(this, "已开始下载，进度看通知栏", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            // 忽略
        }
        super.onDestroy()
    }
}
