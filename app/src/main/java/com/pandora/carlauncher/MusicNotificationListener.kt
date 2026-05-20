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

    // 已知的音乐类应用包名
    private val musicPackages = listOf(
        "cn.kuwo.player",           // 酷我
        "com.tencent.qqmusic",      // QQ音乐
        "com.netease.cloudmusic",   // 网易云
        "com.kugou.android",        // 酷狗
        "cmccwm.mobilemusic",       // 咪咕
        "com.qishui.music",         // 汽水音乐
        "com.spotify.music",        // Spotify
        "com.apple.android.music",  // Apple Music
        "com.google.android.apps.youtube.music" // YouTube Music
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
        // 连接后立即尝试获取当前活跃的媒体会话
        refreshActiveMediaSession()
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

        // 优先使用大文本中的信息（通常包含更完整的歌手信息）
        val finalArtist = if (artist.isNotEmpty() && artist != bigText) artist else bigText

        // 尝试从通知中提取歌词（部分音乐APP会在通知中显示歌词）
        val lyrics = extractLyrics(extras, title, artist)

        if (title.isNotEmpty()) {
            currentTitle = title
            currentArtist = finalArtist
            currentPackageName = pkg
            
            // 更新歌词
            if (lyrics.isNotEmpty() && lyrics != currentLyrics) {
                currentLyrics = lyrics
                onLyricsUpdate?.invoke(lyrics)
                Log.d(TAG, "歌词更新: $lyrics")
            }

            // 获取播放状态
            updatePlayState(pkg)

            onMusicUpdate?.invoke(currentTitle, currentArtist, isPlaying, currentPackageName)
            Log.d(TAG, "歌曲更新: $title - $finalArtist ($pkg)")
        }
    }
    
    /**
     * 从通知中提取歌词
     * 不同音乐APP的歌词位置不同
     */
    private fun extractLyrics(extras: android.os.Bundle, title: String, artist: String): String {
        // 尝试各种可能的歌词字段
        val possibleLyrics = listOf(
            extras.getCharSequence("android.textLines")?.toString(),  // 某些APP的歌词
            extras.getCharSequence("android.messages")?.toString(),   // 消息样式
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),  // 摘要
            extras.getCharSequence("android.bigText")?.toString()     // 大文本
        )
        
        // 查找包含歌词特征（长度适中，不是标题或歌手）的文本
        for (text in possibleLyrics) {
            if (!text.isNullOrEmpty() && 
                text != title && 
                text != artist && 
                text.length > 5 && 
                text.length < 100 &&
                !text.contains("正在播放") &&
                !text.contains("点击播放")) {
                return text.trim()
            }
        }
        
        return ""
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return

        // 如果移除的是当前正在播放的音乐通知，清理状态
        if (sbn.packageName == currentPackageName) {
            // 检查是否还有其他活跃的音乐通知
            refreshActiveMediaSession()
        }
    }

    /**
     * 判断是否为音乐类应用
     */
    private fun isMusicPackage(pkg: String): Boolean {
        return musicPackages.any { pkg == it || pkg.startsWith(it.split(".").first()) }
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

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        activeMediaController = null
        Log.d(TAG, "音乐通知监听已销毁")
    }
}
