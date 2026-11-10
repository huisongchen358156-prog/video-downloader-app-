package com.noah.videodownloader

import com.chaquo.python.Python
import org.json.JSONObject

/** 解析后的一条可选格式。 */
data class VideoFormat(
    val formatId: String,
    val height: Int,
    val ext: String,
    val filesize: Long?,
    val isAudioOnly: Boolean,
    val isCombined: Boolean
) {
    fun label(): String {
        val size = formatBytes(filesize)
        val sizeText = if (size.isEmpty()) "" else " · 约$size"
        return if (isAudioOnly) "仅音频 · $ext$sizeText" else "${height}p · $ext$sizeText"
    }
}

data class VideoInfo(
    val title: String,
    val uploader: String,
    val durationSec: Long?,
    val thumbnail: String?,
    val formats: List<VideoFormat>,
    val maxCombinedHeight: Int,
    val hasHigherSeparate: Boolean
)

sealed class ParseResult {
    data class Success(val info: VideoInfo) : ParseResult()
    data class Failure(val code: String?, val raw: String?) : ParseResult()
}

data class DownloadResult(
    val ok: Boolean,
    val path: String?,
    val size: Long,
    val code: String?,
    val raw: String?
)

/**
 * Kotlin 与 Python（Chaquopy）之间的桥。
 * 所有调用都在后台线程执行，禁止在主线程调用。
 */
object YtDlpBridge {

    private fun module() = Python.getInstance().getModule("downloader")

    fun extractInfo(url: String): ParseResult {
        return try {
            val jsonStr = module().callAttr("extract_info", url).toString()
            val json = JSONObject(jsonStr)
            if (!json.optBoolean("ok", false)) {
                return ParseResult.Failure(
                    json.optString("code", "parse_failed").ifEmpty { "parse_failed" },
                    json.optString("error", "")
                )
            }
            val formats = mutableListOf<VideoFormat>()
            val arr = json.optJSONArray("formats")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    formats.add(
                        VideoFormat(
                            formatId = f.optString("format_id"),
                            height = f.optInt("height", 0),
                            ext = f.optString("ext", "mp4"),
                            filesize = if (f.isNull("filesize")) null else f.optLong("filesize"),
                            isAudioOnly = f.optBoolean("is_audio_only", false),
                            isCombined = f.optBoolean("is_combined", false)
                        )
                    )
                }
            }
            ParseResult.Success(
                VideoInfo(
                    title = json.optString("title", "未命名视频"),
                    uploader = json.optString("uploader", ""),
                    durationSec = if (json.isNull("duration")) null else json.optLong("duration"),
                    thumbnail = json.optString("thumbnail", "").ifEmpty { null },
                    formats = formats,
                    maxCombinedHeight = json.optInt("max_combined_height", 0),
                    hasHigherSeparate = json.optBoolean("has_higher_separate", false)
                )
            )
        } catch (e: Exception) {
            ParseResult.Failure("parse_failed", e.message)
        }
    }

    /**
     * 下载指定格式到 outdir 临时目录；进度由 Python 写进 progressFile（JSON），
     * 调用方轮询读取。返回落盘的临时文件路径（之后由 DownloadService
     * 复制进公共 Download/VideoDownloader/ 目录）。
     */
    fun download(
        url: String,
        formatId: String,
        outdir: String,
        filenameBase: String,
        progressFile: String
    ): DownloadResult {
        return try {
            val jsonStr = module().callAttr(
                "download", url, formatId, outdir, filenameBase, progressFile
            ).toString()
            val json = JSONObject(jsonStr)
            if (!json.optBoolean("ok", false)) {
                return DownloadResult(
                    ok = false,
                    path = null,
                    size = 0,
                    code = json.optString("code", "parse_failed").ifEmpty { "parse_failed" },
                    raw = json.optString("error", "")
                )
            }
            DownloadResult(
                ok = true,
                path = json.optString("path", ""),
                size = json.optLong("size", 0),
                code = null,
                raw = null
            )
        } catch (e: Exception) {
            DownloadResult(false, null, 0, "parse_failed", e.message)
        }
    }
}
