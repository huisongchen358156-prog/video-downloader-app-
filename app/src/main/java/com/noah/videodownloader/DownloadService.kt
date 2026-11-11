package com.noah.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * 前台下载服务：跑 yt-dlp 下载 + 进度通知 + 落盘到公共下载目录 + 写历史。
 * 下载本身在工作线程跑，主线程不阻塞。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_PROGRESS = "com.noah.videodownloader.DOWNLOAD_PROGRESS"
        const val ACTION_FINISHED = "com.noah.videodownloader.DOWNLOAD_FINISHED"
        const val ACTION_FAILED = "com.noah.videodownloader.DOWNLOAD_FAILED"
        // 已有下载在跑时拒绝新的下载请求（不是下载失败，界面只提示、不动进度）。
        const val ACTION_BUSY = "com.noah.videodownloader.DOWNLOAD_BUSY"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ERROR = "error"
        const val EXTRA_URI = "uri"

        const val EXTRA_URL = "url"
        const val EXTRA_FORMAT_ID = "format_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_PLATFORM = "platform"

        private const val CHANNEL_ID = "download"
        private const val NOTIF_ID = 1001

        fun startDownload(
            context: android.content.Context,
            url: String,
            formatId: String,
            fileName: String,
            platform: String
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PLATFORM, platform)
            }
            context.startForegroundService(intent)
        }
    }

    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        createChannel()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "视频下载进度与完成提醒"
        nm.createNotificationChannel(channel)
    }

    private fun notify(id: Int, notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    private fun progressNotification(title: String, percent: Int): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在下载：$title")
            .setContentText("已下载 $percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (running) {
            // V1 一次只跑一个下载：新请求直接拒掉并明确告诉界面，
            // 以前这里是悄悄 return，界面会一直卡在“开始下载…”。
            sendBroadcast(Intent(ACTION_BUSY))
            return START_NOT_STICKY
        }
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: return START_NOT_STICKY
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "video"
        val platform = intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.OTHER.label
        running = true

        startForeground(NOTIF_ID, progressNotification(fileName, 0))

        Thread {
            runDownload(url, formatId, fileName, platform)
            running = false
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, formatId: String, fileName: String, platform: String) {
        // 直连开着时，下载线程里也确保绑到直连网络（解析时绑过的话这里直接返回）。
        if (DirectNetwork.isEnabled(this)) {
            DirectNetwork.ensureBound(applicationContext)
        }

        val tmpDir = File(cacheDir, "dl_tmp").apply { mkdirs() }
        val progressFile = File(cacheDir, "dl_progress.json")
        if (progressFile.exists()) progressFile.delete()

        // 下载线程 + 轮询线程：Python 把进度写进 progressFile，这里每 400ms 读一次。
        var result: DownloadResult? = null
        val dlThread = Thread {
            result = YtDlpBridge.download(url, formatId, tmpDir.absolutePath, fileName, progressFile.absolutePath)
        }
        dlThread.start()
        var lastPercent = -1
        while (dlThread.isAlive) {
            try {
                val percent = readProgressPercent(progressFile)
                if (percent != lastPercent && percent in 0..100) {
                    lastPercent = percent
                    notify(NOTIF_ID, progressNotification(fileName, percent))
                    sendBroadcast(
                        Intent(ACTION_PROGRESS)
                            .putExtra(EXTRA_PERCENT, percent)
                            .putExtra(EXTRA_TITLE, fileName)
                    )
                }
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                break
            }
        }
        try {
            dlThread.join()
        } catch (e: InterruptedException) {
            // ignore
        }

        val res = result
        if (res == null || !res.ok || res.path.isNullOrEmpty()) {
            val msg = friendlyError(res?.code, res?.raw)
            notify(
                NOTIF_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("下载失败")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            )
            sendBroadcast(Intent(ACTION_FAILED).putExtra(EXTRA_ERROR, msg).putExtra(EXTRA_TITLE, fileName))
            return
        }

        val tmpFile = File(res.path)
        val targetUri = copyToPublicDownloads(tmpFile, fileName)
        tmpFile.delete()
        if (targetUri == null) {
            val msg = "下载完成，但保存到“下载”文件夹失败，请重试"
            notify(
                NOTIF_ID,
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("保存失败")
                    .setContentText(msg)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .build()
            )
            sendBroadcast(Intent(ACTION_FAILED).putExtra(EXTRA_ERROR, msg).putExtra(EXTRA_TITLE, fileName))
            return
        }

        // 写历史。
        val store = HistoryStore(File(filesDir, "history.json"))
        store.add(
            HistoryItem(
                id = System.currentTimeMillis().toString(),
                title = fileName,
                platform = platform,
                url = url,
                uri = targetUri.toString(),
                fileName = tmpFile.name,
                sizeBytes = res.size,
                downloadedAt = System.currentTimeMillis()
            )
        )

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, mimeOfFile(tmpFile.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("下载完成：$fileName")
                .setContentText("已保存到 下载/VideoDownloader/，点此打开")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
        sendBroadcast(
            Intent(ACTION_FINISHED)
                .putExtra(EXTRA_TITLE, fileName)
                .putExtra(EXTRA_URI, targetUri.toString())
        )
    }

    private fun readProgressPercent(progressFile: File): Int {
        return try {
            if (!progressFile.exists()) return -1
            val json = JSONObject(progressFile.readText())
            json.optInt("percent", -1)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 把临时文件复制进公共目录 Download/VideoDownloader/。
     * API 29+ 用 MediaStore（无需存储权限）；API 26–28 用公共目录直写（需 WRITE_EXTERNAL_STORAGE）。
     */
    private fun copyToPublicDownloads(tmpFile: File, fileName: String): Uri? {
        val displayName = tmpFile.name
        val mime = mimeOfFile(displayName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VideoDownloader")
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = contentResolver.insert(collection, values) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(tmpFile).use { input -> input.copyTo(out) }
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "VideoDownloader"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, displayName)
                tmpFile.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            null
        }
    }
}
