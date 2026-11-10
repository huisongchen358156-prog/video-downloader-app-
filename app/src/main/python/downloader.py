# -*- coding: utf-8 -*-
"""yt-dlp 封装：供 Kotlin（Chaquopy）调用。

只提供两个入口：
  extract_info(url) -> JSON 字符串
  download(url, format_id, outdir, filename_base, progress_file) -> JSON 字符串

设计说明：
- 解析与下载都在本机进行，不经过任何中转服务器；链接只发给视频网站本身。
- V1 不做 Cookie 导入、不做 JS Hook：需要登录/风控的链接会返回明确的错误码，
  Kotlin 侧再翻译成中文提示。
- 清晰度只提供“合并格式”（视频+音频一体，可直接下载）和“仅音频”：
  1080p 等分离音视频需要 ffmpeg 合并，手机上没有现成的 ffmpeg，
  所以这类格式不在列表里，见 extract_info 返回的 has_higher_separate。
  抖音/快手/小红书等平台的源文件本身就是合并格式，1080p 照常可选。
"""

import json
import os
import re

import yt_dlp


def _error_code(message):
    m = (message or "").lower()
    if "login required" in m or "log in" in m or "cookies" in m or "sign in" in m:
        return "login_required"
    if "private" in m:
        return "login_required"
    if "video unavailable" in m or "not available" in m or "removed" in m \
            or "deleted" in m or "404" in m:
        return "not_found"
    if "unsupported url" in m or "no video formats" in m:
        return "unsupported"
    if "timed out" in m or "connection" in m or "network" in m \
            or "name or service not known" in m or "temporary failure" in m:
        return "network"
    return "parse_failed"


def _ok(payload):
    payload["ok"] = True
    return json.dumps(payload, ensure_ascii=False)


def _fail(message):
    return json.dumps(
        {"ok": False, "code": _error_code(message), "error": str(message)[:300]},
        ensure_ascii=False,
    )


def _pick_formats(raw_formats):
    """从 yt-dlp 返回的原始格式列表里挑出 V1 能直接下载的那些。

    返回 (formats, max_combined_height, has_higher_separate)：
    - formats：合并格式（视频+音频一体，同一清晰度只留一个最好的）+
      仅音频（只留一个文件最大的，通常码率最高），按清晰度从高到低排，音频放最后。
    - has_higher_separate：是否存在比最高合并清晰度更高的纯视频流
      （那种需要 ffmpeg 合并，V1 下不了，只用来提示）。

    注意两个之前写错的点（2026-11-10 代码审阅发现）：
    1. 仅音频以前留的是排序后第一个，也就是文件最小的（码率最低），
       现在改成显式比较，只留文件最大的。
    2. has_higher_separate 以前在循环里边算边比，纯视频流可能排在同清晰度
       的合并格式前面，导致明明有合并 1080p 也误报“有更高清晰度”；
       现在先记纯视频流的最高清晰度，循环结束后再跟最终的合并最高值比。
    """
    formats = []
    seen_heights = set()
    max_combined_height = 0
    video_only_max_height = 0
    best_audio = None
    best_audio_key = -1

    # 先按“清晰度 + 文件大小”排个序，方便后面按清晰度去重时优先留 mp4、大文件。
    def _sort_key(f):
        return (
            f.get("height") or 0,
            1 if (f.get("ext") == "mp4") else 0,
            f.get("filesize") or f.get("filesize_approx") or 0,
        )

    for f in sorted(raw_formats, key=_sort_key):
        vcodec = f.get("vcodec")
        acodec = f.get("acodec")
        if vcodec in (None, "none") and acodec in (None, "none"):
            continue
        is_audio_only = vcodec in (None, "none")
        is_combined = (vcodec not in (None, "none")) and (acodec not in (None, "none"))
        height = f.get("height") or 0
        if is_combined:
            if height > max_combined_height:
                max_combined_height = height
            # 同一清晰度只留一个（排序后靠后的就是 mp4/更大的那个）。
            if height in seen_heights:
                formats = [x for x in formats if not (
                    x["height"] == height and not x["is_audio_only"])]
            seen_heights.add(height)
            formats.append({
                "format_id": str(f.get("format_id")),
                "height": height,
                "ext": f.get("ext") or "mp4",
                "filesize": f.get("filesize") or f.get("filesize_approx"),
                "is_audio_only": False,
                "is_combined": True,
            })
        elif is_audio_only:
            # 仅音频只留一个最好的：同一视频各音频格式时长相同，
            # 文件越大码率越高，显式比大小，不依赖排序顺序。
            key = f.get("filesize") or f.get("filesize_approx") or 0
            if key > best_audio_key:
                best_audio_key = key
                best_audio = {
                    "format_id": str(f.get("format_id")),
                    "height": 0,
                    "ext": f.get("ext") or "m4a",
                    "filesize": f.get("filesize") or f.get("filesize_approx"),
                    "is_audio_only": True,
                    "is_combined": False,
                }
        else:
            # 纯视频流：没有音频，需要 ffmpeg 合并，V1 不提供；
            # 只记最高清晰度，循环结束后再跟合并最高值比较。
            if height > video_only_max_height:
                video_only_max_height = height

    if best_audio is not None:
        formats.append(best_audio)

    # 清晰度从高到低，音频放最后。
    formats.sort(key=lambda x: (x["is_audio_only"], -(x["height"] or 0)))
    has_higher_separate = video_only_max_height > max_combined_height
    return formats, max_combined_height, has_higher_separate


def extract_info(url):
    """解析链接，返回标题/作者/时长/封面/可选格式。"""
    opts = {
        "quiet": True,
        "noplaylist": True,
        "skip_download": True,
        "no_warnings": True,
        "socket_timeout": 30,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:  # noqa: BLE001 - 任何解析失败都转成错误 JSON
        return _fail(str(e))

    if not info or info.get("entries"):
        # 合集/频道页：V1 只支持单个视频链接。
        return _fail("Unsupported URL: playlist or channel page")

    raw_formats = info.get("formats") or []
    formats, max_combined_height, has_higher_separate = _pick_formats(raw_formats)

    return _ok({
        "title": info.get("title") or "未命名视频",
        "uploader": info.get("uploader") or info.get("channel") or "",
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail") or "",
        "formats": formats,
        "max_combined_height": max_combined_height,
        "has_higher_separate": has_higher_separate,
    })


def download(url, format_id, outdir, filename_base, progress_file):
    """下载指定格式。进度写进 progress_file（JSON），Kotlin 侧轮询读取。"""
    os.makedirs(outdir, exist_ok=True)

    def hook(d):
        try:
            status = d.get("status")
            if status == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                percent = int(done * 100 / total) if total else 0
                payload = {"status": "downloading", "percent": percent,
                           "downloaded": done, "total": total}
            elif status == "finished":
                payload = {"status": "finished", "percent": 100}
            else:
                return
            with open(progress_file, "w", encoding="utf-8") as fh:
                json.dump(payload, fh)
        except Exception:
            pass

    # 文件名中的非法字符由 Kotlin 侧先洗过，这里再兜底一次。
    safe_base = re.sub(r'[\\/:*?"<>|]', "_", filename_base or "video").strip(" .") or "video"
    opts = {
        "quiet": True,
        "noplaylist": True,
        "format": format_id,
        "outtmpl": os.path.join(outdir, safe_base + ".%(ext)s"),
        "progress_hooks": [hook],
        "no_warnings": True,
        "socket_timeout": 60,
        "retries": 3,
    }
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
    except Exception as e:  # noqa: BLE001
        return _fail(str(e))

    path = ""
    try:
        req = (info or {}).get("requested_downloads") or []
        if req:
            path = req[0].get("filepath") or ""
    except Exception:
        path = ""
    if not path or not os.path.exists(path):
        # 兜底：按文件名前缀找实际落盘的文件。
        for name in os.listdir(outdir):
            if name.startswith(safe_base + "."):
                path = os.path.join(outdir, name)
                break
    if not path or not os.path.exists(path):
        return _fail("download finished but file not found")
    return _ok({"path": path, "size": os.path.getsize(path)})
