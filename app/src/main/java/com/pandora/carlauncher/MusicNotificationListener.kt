package com.pandora.carlauncher

import android.app.Notification
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 音乐通知监听服务
 * 监听所有音乐APP的通知和MediaSession，获取播放状态
 * 参考布丁桌面的 MediaControllerService 实现
 */
class MusicNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicNotify"

        @Volatile
        var isRunning = false

        @Volatile
        var currentTitle = ""

        @Volatile
        var currentArtist = ""

        @Volatile
        var isPlaying = false

        @Volatile
        var currentPackageName = ""

        // 当前活跃的 MediaController，供 FloatingMusicService 使用
        @Volatile
        var activeMediaController: MediaController? = null

        // 回调接口
        var onMusicUpdate: ((title: String, artist: String, isPlaying: Boolean, pkg: String) -> Unit)? = null

        // 当前歌词
        @Volatile
        var currentLyrics = ""

        // 歌词更新回调
        var onLyricsUpdate: ((lyrics: String) -> Unit)? = null
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaControllerCallback: MediaController.Callback? = null

    // 已知的音乐类应用包名（完整列表，与 MainActivity 保持一致）
    private val musicPackages = listOf(
        // 酷我音乐 - 全版本
        "cn.kuwo.player",
        "cn.kuwo.kwmusiccar",
        "cn.kuwo.car",
        "com.kuwo.player",
        "com.kuwo.vehicle",
        "cn.kuwo.kwmusic",
        "cn.kuwo.kwmusicforcar",
        "cn.kuwo.kwmusicauto",
        "cn.kuwo.kwmusiccarsnp",
        "cn.kuwo.kwmusiccarhd",
        "cn.kuwo.kwmusiccarvip",
        "cn.kuwo.kuwomusiccar",
        "cn.kuwo.kuwomusiccarsnp",
        // QQ音乐 - 全版本
        "com.tencent.qqmusic",
        "com.tencent.qqmusic.car",
        "com.tencent.qqmusiclite",
        "com.tencent.qqmusic.iot",
        "com.tencent.qqmusic.vehicle",
        "com.tencent.qqmusic.pad",
        "com.tencent.qqmusic.hd",
        "com.tencent.qqmusiccar",
        "com.tencent.qqmusic.mi",
        "com.tencent.qqmusic.vip",
        // 网易云音乐 - 全版本
        "com.netease.cloudmusic",
        "com.netease.cloudmusic.car",
        "com.netease.cloudmusic.lite",
        "com.netease.cloudmusic.hd",
        "com.netease.cloudmusic.auto",
        // 酷狗音乐 - 全版本
        "com.kugou.android",
        "com.kugou.android.lite",
        "com.kugou.player",
        "com.kugou.android.auto",
        "com.kugou.android.car",
        "com.kugou.android.hd",
        "com.kugou.android.pad",
        // 汽水音乐
        "com.qishui.music",
        "com.qishui.music.tycx",
        "com.qishui.music.auto",
        "com.qishui.music.car",
        // 波点音乐
        "com.dotpoints.bodian",
        "com.dotpoints.bodian.car",
        // 咪咕音乐
        "cmccwm.mobilemusic",
        "cmccwm.mobilemusic.car",
        "cmccwm.mobilemusic.hd",
        // Spotify
        "com.spotify.music",
        "com.spotify.music.lite",
        // Apple Music
        "com.apple.android.music",
        // YouTube Music
        "com.google.android.apps.youtube.music",
        // 百度音乐/千千音乐
        "com.ting.mp3.android",
        "com.baidu.music",
        // 虾米音乐
        "com.xiami.music",
        // 喜马拉雅
        "com.ximalaya.ting.android",
        "com.ximalaya.ting.android.car",
        // 懒人听书
        "com.lrts.lots",
        // 番茄畅听
        "com.xs.fm",
        // 其他音乐播放器
        "com.jiongya.vehiclemusic",
        "com.musicplayer.android",
        "com.android.music",
        "com.sonyericsson.music",
        "com.miui.player",
        "com.coloros.music",
        "com.heytap.music",
        "com.vivo.music",
        "com.huawei.music",
        "com.samsung.android.app.music",
        "com.samsung.android.app.music.chn",
        "com.google.android.music",
        "com.amazon.mp3",
        "com.sonyericsson.zsystem",
        "com.luna.music",
        // 抖音音乐
        "com.bytedance.byteautoservices",
        "com.bytedance.byteautoservice3"
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        } catch (e: Exception) {
            Log.e(TAG, "获取 MediaSessionManager 失败", e)
        }
        Log.d(TAG, "音乐通知监听已启动")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "通知监听器已连接")
        // 连接后立即尝试获取当前活跃的媒体会话和通知
        refreshActiveMediaSession()
        // 同时获取当前活跃的通知，提取歌词
        refreshActiveNotifications()
    }

    /**
     * 刷新活跃通知，提取歌词
     * 在通知监听器连接时调用，获取已有音乐通知中的歌词
     */
    private fun refreshActiveNotifications() {
        try {
            val activeNotifications = activeNotifications ?: return
            for (sbn in activeNotifications) {
                val pkg = sbn.packageName
                if (!isMusicPackage(pkg)) continue

                val extras = sbn.notification?.extras ?: continue
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                val tickerText = sbn.notification?.tickerText?.toString() ?: ""
                val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""

                val lyrics = extractLyrics(extras, title, artist, bigText, subText, tickerText, infoText)

                if (lyrics.isNotEmpty() && lyrics != currentLyrics) {
                    currentLyrics = lyrics
                    onLyricsUpdate?.invoke(lyrics)
                    Log.d(TAG, "从已有通知中提取歌词: $lyrics")
                }

                // 如果有标题，也更新歌曲信息
                if (title.isNotEmpty() && title != currentTitle) {
                    val finalArtist = if (artist.isNotEmpty()) {
                        artist
                    } else if (bigText.isNotEmpty() && lyrics.isEmpty()) {
                        bigText
                    } else {
                        subText
                    }

                    currentTitle = title
                    currentArtist = finalArtist
                    currentPackageName = pkg
                    updatePlayState(pkg)
                    onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
                    Log.d(TAG, "从已有通知中提取歌曲: $title - $finalArtist ($pkg)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新活跃通知失败", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkg = sbn.packageName

        // 过滤音乐类应用
        if (!isMusicPackage(pkg)) return

        // 从通知中提取歌曲信息
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val tickerText = sbn.notification?.tickerText?.toString() ?: ""
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""

        // 尝试从通知中提取歌词
        // 许多音乐APP（网易云音乐、酷狗等）将当前歌词放在 EXTRA_BIG_TEXT 中
        val lyrics = extractLyrics(extras, title, artist, bigText, subText, tickerText, infoText)

        // 歌词处理：无论标题是否为空都处理歌词
        // 因为很多音乐APP在歌词更新时只更新 bigText，不重新发送标题
        if (lyrics.isNotEmpty() && lyrics != currentLyrics) {
            currentLyrics = lyrics
            onLyricsUpdate?.invoke(lyrics)
            Log.d(TAG, "歌词更新: $lyrics")
        } else if (lyrics.isEmpty() && currentLyrics.isNotEmpty()) {
            // 歌词为空时清除
            currentLyrics = ""
            onLyricsUpdate?.invoke("")
        }

        // 判断 artist 和 bigText 的关系
        // 如果 bigText 与 artist 不同且不是歌词，则 bigText 可能是更完整的歌手信息
        // 如果 bigText 是歌词，则保留 artist 作为歌手
        val finalArtist = if (artist.isNotEmpty()) {
            artist
        } else if (bigText.isNotEmpty() && lyrics.isEmpty()) {
            bigText
        } else {
            subText
        }

        if (title.isNotEmpty()) {
            currentTitle = title
            currentArtist = finalArtist
            currentPackageName = pkg

            // 获取播放状态
            updatePlayState(pkg)

            onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
            Log.d(TAG, "歌曲更新: $title - $finalArtist ($pkg)" +
                if (lyrics.isNotEmpty()) " 歌词: $lyrics" else "")
        } else if (pkg == currentPackageName) {
            // 标题为空但包名匹配，可能是歌词更新通知
            // 如果有 artist 信息也更新
            if (finalArtist.isNotEmpty() && finalArtist != currentArtist) {
                currentArtist = finalArtist
            }
            Log.d(TAG, "歌词/状态更新通知 ($pkg), 歌词: $lyrics")
        }
    }

    /**
     * 从通知中提取歌词
     *
     * 不同音乐APP的歌词存放位置：
     * - 网易云音乐：EXTRA_BIG_TEXT 存放当前歌词行
     * - 酷狗音乐：EXTRA_BIG_TEXT 存放当前歌词行
     * - QQ音乐：EXTRA_BIG_TEXT 或 tickerText 存放歌词
     * - 酷我音乐：EXTRA_SUMMARY_TEXT 或 EXTRA_INFO_TEXT 存放歌词
     *
     * 歌词特征：
     * - 与标题和歌手不同
     * - 长度适中（通常 2~500 字符）
     * - 不包含播放状态提示文字
     */
    private fun extractLyrics(
        extras: android.os.Bundle,
        title: String,
        artist: String,
        bigText: String,
        subText: String,
        tickerText: String,
        infoText: String
    ): String {
        // 排除的非歌词文本关键词
        val nonLyricKeywords = listOf(
            "正在播放", "点击播放", "已暂停", "正在缓冲", "播放中",
            "playing", "paused", "buffering", "正在加载",
            "锁屏", "桌面歌词", "通知栏歌词"
        )

        // 收集所有候选歌词文本（按优先级排序）
        val candidates = listOf(bigText, subText, infoText, tickerText)

        for (text in candidates) {
            if (text.isNullOrEmpty()) continue
            val trimmed = text.trim()

            // 必须与标题和歌手完全不同
            if (trimmed == title || trimmed == artist) continue

            // 如果文本完全包含标题，且长度差距很小（≤5），可能是"标题 - 歌手"格式，跳过
            if (title.isNotEmpty() && trimmed.contains(title) && trimmed.length <= title.length + 5) continue

            // 排除非歌词文本
            if (nonLyricKeywords.any { trimmed.contains(it, ignoreCase = true) }) continue

            // 歌词长度通常在 2~500 字符之间
            if (trimmed.length < 2 || trimmed.length > 500) continue

            // 排除纯文件名路径
            if (trimmed.contains(".mp3") || trimmed.contains(".flac") ||
                trimmed.contains(".wav") || trimmed.contains("/storage")) continue

            // 排除专辑信息（通常包含"专辑"或"album"）
            if (trimmed.contains("专辑") && trimmed.length < 20) continue
            if (trimmed.contains("album", ignoreCase = true) && trimmed.length < 20) continue

            return trimmed
        }

        return ""
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return

        // 如果移除的是当前正在播放的音乐通知，清理状态
        if (sbn.packageName == currentPackageName) {
            // 清除歌词
            if (currentLyrics.isNotEmpty()) {
                currentLyrics = ""
                onLyricsUpdate?.invoke("")
            }
            // 检查是否还有其他活跃的音乐通知
            refreshActiveMediaSession()
        }
    }

    /**
     * 判断是否为音乐类应用
     * 精确匹配包名或匹配子包名（以 "." 分隔）
     */
    private fun isMusicPackage(pkg: String): Boolean {
        return musicPackages.any { pkg == it || pkg.startsWith("$it.") }
    }

    /**
     * 通过 MediaSessionManager 获取播放状态
     */
    private fun updatePlayState(pkg: String) {
        try {
            val controllers = mediaSessionManager?.getActiveSessions(null) ?: return
            for (controller in controllers) {
                if (controller.packageName == pkg) {
                    val state = controller.playbackState
                    isPlaying = state != null &&
                            state.state == PlaybackState.STATE_PLAYING
                    activeMediaController = controller
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取播放状态失败", e)
        }
    }

    /**
     * 刷新活跃的媒体会话
     * 在通知监听器连接或通知移除时调用
     */
    private fun refreshActiveMediaSession() {
        try {
            val controllers = mediaSessionManager?.getActiveSessions(null) ?: return
            for (controller in controllers) {
                if (isMusicPackage(controller.packageName)) {
                    val metadata = controller.metadata
                    if (metadata != null) {
                        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                        val state = controller.playbackState

                        if (title.isNotEmpty()) {
                            currentTitle = title
                            currentArtist = artist
                            currentPackageName = controller.packageName
                            isPlaying = state != null &&
                                    state.state == PlaybackState.STATE_PLAYING
                            activeMediaController = controller

                            // 注册 MediaController 回调，监听元数据变化
                            registerMediaControllerCallback(controller)

                            onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
                            Log.d(TAG, "刷新媒体会话: $title - $artist")
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新活跃媒体会话失败", e)
        }
    }

    /**
     * 注册 MediaController 回调
     * 监听播放状态和元数据变化，实时更新歌词
     */
    private fun registerMediaControllerCallback(controller: MediaController) {
        // 先注销旧的回调
        mediaControllerCallback?.let { activeMediaController?.unregisterCallback(it) }

        mediaControllerCallback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(s: PlaybackState?) {
                super.onPlaybackStateChanged(s)
                s?.let {
                    isPlaying = it.state == PlaybackState.STATE_PLAYING
                    onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
                }
            }

            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                super.onMetadataChanged(metadata)
                metadata?.let {
                    val title = it.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                    val artist = it.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    if (title.isNotEmpty()) {
                        currentTitle = title
                        currentArtist = artist
                        onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
                        Log.d(TAG, "元数据更新: $title - $artist")
                    }
                }
            }
        }
        mediaControllerCallback?.let { controller.registerCallback(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销 MediaController 回调
        mediaControllerCallback?.let { activeMediaController?.unregisterCallback(it) }
        mediaControllerCallback = null
        isRunning = false
        activeMediaController = null
        Log.d(TAG, "音乐通知监听已销毁")
    }
}
