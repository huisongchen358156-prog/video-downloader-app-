package com.noah.videodownloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条下载历史。uri 是 MediaStore 的 content:// 地址，用于打开/分享。 */
data class HistoryItem(
    val id: String,
    val title: String,
    val platform: String,
    val url: String,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadedAt: Long
)

/**
 * 历史记录：存在 App 内部文件 history.json（最简单的可靠方案，不引 Room）。
 * 文件损坏时返回空列表，不闪退。
 */
class HistoryStore(private val file: File) {

    @Synchronized
    fun load(): List<HistoryItem> {
        return try {
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        platform = o.optString("platform"),
                        url = o.optString("url"),
                        uri = o.optString("uri"),
                        fileName = o.optString("fileName"),
                        sizeBytes = o.optLong("sizeBytes", 0),
                        downloadedAt = o.optLong("downloadedAt", 0)
                    )
                )
            }
            list.sortedByDescending { it.downloadedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(item: HistoryItem) {
        val list = load().toMutableList()
        list.removeAll { it.id == item.id }
        list.add(0, item)
        save(list)
    }

    @Synchronized
    fun remove(id: String) {
        save(load().filter { it.id != id })
    }

    private fun save(list: List<HistoryItem>) {
        try {
            val arr = JSONArray()
            for (item in list) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("title", item.title)
                o.put("platform", item.platform)
                o.put("url", item.url)
                o.put("uri", item.uri)
                o.put("fileName", item.fileName)
                o.put("sizeBytes", item.sizeBytes)
                o.put("downloadedAt", item.downloadedAt)
                arr.put(o)
            }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // 历史写失败不影响下载本身，静默忽略。
        }
    }
}
