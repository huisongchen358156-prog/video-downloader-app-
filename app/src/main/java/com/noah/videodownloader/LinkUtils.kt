package com.noah.videodownloader

/**
 * 纯 Kotlin 逻辑：不依赖任何 Android / Chaquopy API。
 * 目标是这部分可以用 kotlinc 单独编译测试（见 BUILD_NOTES.md）。
 */

enum class Platform(val label: String) {
    DOUYIN("抖音"),
    KUAISHOU("快手"),
    BILIBILI("哔哩哔哩"),
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    X("X"),
    TIKTOK("TikTok"),
    XIAOHONGSHU("小红书"),
    WEIBO("微博"),
    FACEBOOK("Facebook"),
    TWITCH("Twitch"),
    VIMEO("Vimeo"),
    REDDIT("Reddit"),
    DAILYMOTION("Dailymotion"),
    THREADS("Threads"),
    PINTEREST("Pinterest"),
    TUMBLR("Tumblr"),
    LINKEDIN("LinkedIn"),
    OTHER("其他")
}

/** 从链接里取 host（小写），用于需要精确匹配域名的平台判断。 */
private fun hostOf(url: String): String {
    val after = url.substringAfter("://", "")
    if (after.isEmpty()) return ""
    return after.substringBefore('/').substringBefore('?').substringBefore('#')
}

/** 从链接域名判断平台。 */
fun detectPlatform(url: String): Platform {
    val lower = url.lowercase()
    val host = hostOf(lower)
    return when {
        "douyin.com" in lower -> Platform.DOUYIN
        "kuaishou.com" in lower || "kwai.com" in lower || "v.kuaishou" in lower -> Platform.KUAISHOU
        "bilibili.com" in lower || "b23.tv" in lower -> Platform.BILIBILI
        "youtube.com" in lower || "youtu.be" in lower -> Platform.YOUTUBE
        "instagram.com" in lower -> Platform.INSTAGRAM
        // 注意：不能用 "x.com" in lower 这种子串匹配，box.com 这类域名里也含
        // "x.com"，会被误认成 X；改成按 host 精确匹配。
        host == "x.com" || host.endsWith(".x.com") || "twitter.com" in lower -> Platform.X
        "tiktok.com" in lower -> Platform.TIKTOK
        "xiaohongshu.com" in lower || "xhslink.com" in lower -> Platform.XIAOHONGSHU
        "weibo.com" in lower || "weibo.cn" in lower -> Platform.WEIBO
        "facebook.com" in lower || "fb.watch" in lower || "fb.com" in lower -> Platform.FACEBOOK
        "twitch.tv" in lower -> Platform.TWITCH
        "vimeo.com" in lower -> Platform.VIMEO
        "reddit.com" in lower || "redd.it" in lower -> Platform.REDDIT
        "dailymotion.com" in lower || "dai.ly" in lower -> Platform.DAILYMOTION
        "threads.net" in lower || "threads.com" in lower -> Platform.THREADS
        "pinterest.com" in lower || "pin.it" in lower -> Platform.PINTEREST
        "tumblr.com" in lower -> Platform.TUMBLR
        "linkedin.com" in lower -> Platform.LINKEDIN
        else -> Platform.OTHER
    }
}

/** 按文件名后缀取 MIME 类型（打开/分享历史记录时用；音频别再按 video/* 打开）。 */
fun mimeOfFile(name: String): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        else -> "video/*"
    }
}

private val URL_REGEX = Regex("https?://[^\\s\"'<>\\[\\]]+")

/** 从一段分享文本里抠出第一条链接（微信/QQ/抖音分享文案常带中文说明）。 */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_REGEX.find(text) ?: return null
    // 去掉末尾常被粘上的标点/括号/中文句号（含全角括号，微信/QQ 分享文案常用）。
    return match.value.trimEnd(')', ']', '}', '。', '，', '；', '、', '.', ',', ';', '!', '?', '！', '？', '）', '】', '」', '》')
}

/** 把视频标题洗成安全文件名：去非法字符、去首尾空格和点、限长。 */
fun sanitizeFilename(title: String): String {
    var name = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ' ')
    if (name.isEmpty()) name = "video"
    // ext4 / MediaStore 文件名建议别太长，按字符截断到 100。
    if (name.length > 100) name = name.substring(0, 100).trim().trim('.', ' ')
    if (name.isEmpty()) name = "video"
    return name
}

/** 秒 -> "1:05:09" / "3:24"；未知返回 "--"。 */
fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 字节 -> "85.3 MB" / "12 KB"；未知返回 ""。 */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return ""
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }
}

/** 把 Python 返回的错误码翻译成中文提示。 */
fun friendlyError(code: String?, raw: String?): String {
    return when (code) {
        "login_required" -> "该链接需要登录才能查看，请换个公开链接试试（V1 不支持导入 Cookie）"
        "not_found" -> "视频已被删除或不存在，请换个链接试试"
        "unsupported" -> "不支持这个链接，请确认是单个视频的分享链接（不支持合集/频道页）"
        "network" -> "网络连接失败，请检查网络后重试"
        "parse_failed" -> "解析失败，请换个链接试试"
        else -> "下载失败：${raw?.take(80) ?: "未知错误"}，请换个链接试试"
    }
}
