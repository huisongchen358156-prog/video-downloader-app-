package com.noah.videodownloader

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 使用说明与已知限制（静态文本，写在 App 里，用户随时能看）。 */
class HelpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        findViewById<TextView>(R.id.help_text).text = HELP_TEXT
    }

    companion object {
        const val HELP_TEXT = """
使用方法
1. 在抖音、快手、哔哩哔哩、YouTube、Instagram、X、TikTok、小红书里找到视频，点“分享”→“复制链接”。
2. 回到本 App：链接会自动填入（或点“粘贴”），再点“解析”。
3. 解析成功后选一个清晰度，点“下载”。视频保存在手机“下载/VideoDownloader/”文件夹里。
4. 也可以在别的 App 里直接点“分享”→选“视频下载助手”，链接会自动带过来。
5. 历史记录里点一条可以打开播放，长按可以分享或删除记录。

已知限制（V1，如实说明）
• 部分平台（抖音、快手、小红书等）有时会要求登录或触发风控，yt-dlp 解析失败时会提示“需要登录/解析失败”。V1 不提供导入 Cookie、扫码登录等功能，这是 V2 再做的事，不要反复重试同一个链接。
• 清晰度只提供“视频+音频一体”的合并格式和“仅音频”。YouTube 等平台的 1080p 及以上通常是音视频分开的，需要 ffmpeg 合并，手机上 V1 暂不支持，所以解析后可能最高只到 720p；抖音/快手等平台的源文件本身就是合并格式，1080p 照常可选。
• 需要登录、付费、会员专享、私密或已被删除的视频都下不了。
• 每次解析和下载都要联网；解析速度取决于视频网站的响应。
• APK 体积较大（约 40MB 以上），因为内置了 Python 运行环境和 yt-dlp。

隐私
• 链接只发给视频网站本身做解析，不经过任何中转服务器；本 App 不收集账号、不上传你的链接和下载记录。
• 下载的视频只存在你的手机本地。

请注意
• 只下载你有权保存的公开视频，不要下载、传播他人享有版权的付费内容；遵守各平台的使用规则。
• 各平台随时可能改版导致解析失败，遇到这种情况一般是等 yt-dlp 更新版本，不代表 App 坏了。

V2 计划
• 导入 Cookie / 扫码登录后下载需登录的视频；1080p+ 合并下载（内置 ffmpeg）；批量下载；下载到指定文件夹。
"""
    }
}
