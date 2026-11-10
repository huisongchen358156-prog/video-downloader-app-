package com.noah.videodownloader

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var inputLink: EditText
    private lateinit var platformLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var infoCard: LinearLayout
    private lateinit var thumbView: ImageView
    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var formatHint: TextView
    private lateinit var formatList: ListView
    private lateinit var downloadButton: Button
    private lateinit var retryButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var historyList: ListView
    private lateinit var historyEmpty: TextView

    private var currentInfo: VideoInfo? = null
    private var currentUrl: String = ""
    private var selectedFormat = -1
    private var isDownloading = false

    // 供“重试”使用：上一次下载的参数。
    private var lastDownloadUrl = ""
    private var lastFormatId = ""
    private var lastFileName = ""
    private var lastPlatform = ""

    private lateinit var historyStore: HistoryStore
    private var historyItems: List<HistoryItem> = emptyList()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_PROGRESS -> {
                    val percent = intent.getIntExtra(DownloadService.EXTRA_PERCENT, 0)
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = percent
                    progressText.text = "下载中 $percent%"
                }
                DownloadService.ACTION_FINISHED -> {
                    isDownloading = false
                    progressBar.visibility = View.GONE
                    progressText.text = "下载完成"
                    statusText.text = "已保存到 下载/VideoDownloader/：${intent.getStringExtra(DownloadService.EXTRA_TITLE)}"
                    retryButton.visibility = View.GONE
                    downloadButton.isEnabled = true
                    refreshHistory()
                    Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
                }
                DownloadService.ACTION_FAILED -> {
                    isDownloading = false
                    progressBar.visibility = View.GONE
                    val msg = intent.getStringExtra(DownloadService.EXTRA_ERROR) ?: "下载失败"
                    progressText.text = "下载失败"
                    statusText.text = msg
                    retryButton.visibility = View.VISIBLE
                    downloadButton.isEnabled = true
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
                DownloadService.ACTION_BUSY -> {
                    // 已有下载在跑，本次请求被服务拒绝（不是当前下载失败）：
                    // 只提示，不动进度条和下载按钮的状态，等当前下载跑完再说。
                    Toast.makeText(this@MainActivity, "已有下载进行中，请等它完成", Toast.LENGTH_SHORT).show()
                    statusText.text = "已有下载进行中，请等当前下载完成"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        setContentView(R.layout.activity_main)
        historyStore = HistoryStore(File(filesDir, "history.json"))

        inputLink = findViewById(R.id.input_link)
        platformLabel = findViewById(R.id.platform_label)
        statusText = findViewById(R.id.status_text)
        infoCard = findViewById(R.id.info_card)
        thumbView = findViewById(R.id.thumb_view)
        titleText = findViewById(R.id.title_text)
        metaText = findViewById(R.id.meta_text)
        formatHint = findViewById(R.id.format_hint)
        formatList = findViewById(R.id.format_list)
        downloadButton = findViewById(R.id.download_button)
        retryButton = findViewById(R.id.retry_button)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        historyList = findViewById(R.id.history_list)
        historyEmpty = findViewById(R.id.history_empty)

        formatList.choiceMode = ListView.CHOICE_MODE_SINGLE
        formatList.setOnItemClickListener { _, _, position, _ ->
            selectedFormat = position
            // 下载进行中不开放第二个下载（服务侧也会拒掉，这里先拦住不让点）。
            downloadButton.isEnabled = !isDownloading
        }

        findViewById<Button>(R.id.paste_button).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.parse_button).setOnClickListener { parseLink() }
        downloadButton.setOnClickListener { startDownload() }
        retryButton.setOnClickListener { retryDownload() }
        findViewById<Button>(R.id.help_button).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        historyList.setOnItemClickListener { _, _, position, _ -> openHistoryItem(position) }
        historyList.setOnItemLongClickListener { _, _, position, _ ->
            showHistoryMenu(position)
            true
        }

        // Android 14（API 34）起动态注册广播必须声明是否对外导出；
        // 本 App 的广播只在自己内部用，声明为不导出。
        val downloadFilter = IntentFilter().apply {
            addAction(DownloadService.ACTION_PROGRESS)
            addAction(DownloadService.ACTION_FINISHED)
            addAction(DownloadService.ACTION_FAILED)
            addAction(DownloadService.ACTION_BUSY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, downloadFilter)
        }

        handleIncomingIntent(intent)
        refreshHistory()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        autoDetectClipboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // 未注册时忽略。
        }
    }

    /** 处理从别的 App 分享过来的链接。 */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = extractUrl(shared)
            if (url != null) {
                inputLink.setText(url)
                updatePlatformLabel(url)
                statusText.text = "已填入分享来的链接，点“解析”继续"
            }
        }
    }

    /** 打开 App 时自动检测剪贴板里的链接（输入框为空时才填）。 */
    private fun autoDetectClipboard() {
        if (inputLink.text.isNotBlank()) return
        val url = clipboardUrl() ?: return
        inputLink.setText(url)
        updatePlatformLabel(url)
        Toast.makeText(this, "已自动填入剪贴板里的链接", Toast.LENGTH_SHORT).show()
    }

    private fun clipboardUrl(): String? {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            extractUrl(clip.getItemAt(0)?.text?.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun pasteFromClipboard() {
        val url = clipboardUrl()
        if (url == null) {
            Toast.makeText(this, "剪贴板里没有链接", Toast.LENGTH_SHORT).show()
            return
        }
        inputLink.setText(url)
        updatePlatformLabel(url)
    }

    private fun updatePlatformLabel(url: String) {
        val platform = detectPlatform(url)
        platformLabel.text = "平台：${platform.label}"
    }

    private fun parseLink() {
        val url = extractUrl(inputLink.text.toString())
        if (url == null) {
            Toast.makeText(this, "请先粘贴一个视频链接", Toast.LENGTH_SHORT).show()
            return
        }
        currentUrl = url
        updatePlatformLabel(url)
        val parseButton = findViewById<Button>(R.id.parse_button)
        parseButton.isEnabled = false
        statusText.text = "解析中，请稍候…"
        infoCard.visibility = View.GONE
        retryButton.visibility = View.GONE

        Thread {
            val result = YtDlpBridge.extractInfo(url)
            runOnUiThread {
                parseButton.isEnabled = true
                when (result) {
                    is ParseResult.Success -> showVideoInfo(result.info)
                    is ParseResult.Failure -> {
                        statusText.text = friendlyError(result.code, result.raw)
                    }
                }
            }
        }.start()
    }

    private fun showVideoInfo(info: VideoInfo) {
        currentInfo = info
        selectedFormat = -1
        downloadButton.isEnabled = false
        infoCard.visibility = View.VISIBLE
        titleText.text = info.title
        val uploader = if (info.uploader.isBlank()) "未知作者" else info.uploader
        metaText.text = "$uploader · 时长 ${formatDuration(info.durationSec)}"
        statusText.text = "解析成功，请选择清晰度后下载"
        formatHint.text = if (info.hasHigherSeparate) {
            "注：该视频有更高清晰度，但音视频是分开的，V1 暂不支持合并下载（见“使用说明”）。"
        } else {
            ""
        }
        formatHint.visibility = if (info.hasHigherSeparate) View.VISIBLE else View.GONE

        val labels = info.formats.map { it.label() }
        formatList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_single_choice, labels
        )
        if (info.formats.isEmpty()) {
            statusText.text = "这个视频没有可直接下载的格式，请换个链接试试"
        }

        thumbView.setImageDrawable(null)
        val thumbUrl = info.thumbnail
        if (!thumbUrl.isNullOrBlank()) {
            Thread {
                try {
                    val bitmap = BitmapFactory.decodeStream(URL(thumbUrl).openStream())
                    runOnUiThread { if (bitmap != null) thumbView.setImageBitmap(bitmap) }
                } catch (e: Exception) {
                    // 封面加载失败不影响下载。
                }
            }.start()
        }
    }

    private fun startDownload() {
        val info = currentInfo ?: return
        if (selectedFormat < 0 || selectedFormat >= info.formats.size) {
            Toast.makeText(this, "请先选择一个清晰度", Toast.LENGTH_SHORT).show()
            return
        }
        val format = info.formats[selectedFormat]
        val fileName = sanitizeFilename(info.title)
        lastDownloadUrl = currentUrl
        lastFormatId = format.formatId
        lastFileName = fileName
        lastPlatform = detectPlatform(currentUrl).label

        ensureNotificationPermission()
        isDownloading = true
        downloadButton.isEnabled = false
        retryButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "开始下载…"
        statusText.text = "正在下载：$fileName"
        DownloadService.startDownload(this, currentUrl, format.formatId, fileName, lastPlatform)
    }

    private fun retryDownload() {
        if (lastDownloadUrl.isBlank() || lastFormatId.isBlank()) return
        ensureNotificationPermission()
        isDownloading = true
        retryButton.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressText.text = "重新下载…"
        statusText.text = "正在重试下载：$lastFileName"
        DownloadService.startDownload(this, lastDownloadUrl, lastFormatId, lastFileName, lastPlatform)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun refreshHistory() {
        historyItems = historyStore.load()
        historyEmpty.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val labels = historyItems.map {
            "${it.title} · ${it.platform} · ${fmt.format(Date(it.downloadedAt))}"
        }
        historyList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
    }

    private fun openHistoryItem(position: Int) {
        if (position !in historyItems.indices) return
        val item = historyItems[position]
        try {
            val uri = Uri.parse(item.uri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOfFile(item.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开这个文件，可能已被删除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHistoryMenu(position: Int) {
        if (position !in historyItems.indices) return
        val item = historyItems[position]
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("打开播放", "分享", "删除记录")) { _, which ->
                when (which) {
                    0 -> openHistoryItem(position)
                    1 -> shareHistoryItem(item)
                    2 -> {
                        historyStore.remove(item.id)
                        refreshHistory()
                    }
                }
            }
            .show()
    }

    private fun shareHistoryItem(item: HistoryItem) {
        try {
            val uri = Uri.parse(item.uri)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeOfFile(item.fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享视频"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }
}
