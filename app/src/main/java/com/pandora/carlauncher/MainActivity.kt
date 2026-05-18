package com.pandora.carlauncher

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val PREF_NAME = "panda_launcher_prefs"
        const val KEY_CUSTOM_APPS = "custom_apps"
        const val MAX_CUSTOM_APPS = 5
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MM月dd日 EEEE", Locale.getDefault())

    private lateinit var audioManager: AudioManager
    private var customApps = mutableListOf<CustomApp>()
    private lateinit var gridAdapter: AppGridAdapter

    // 导航类型
    private var currentNavType = "amap" // amap, baidu, tencent

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    // 音乐刷新
    private val musicRefreshHandler = Handler(Looper.getMainLooper())
    private val musicRefreshRunnable = object : Runnable {
        override fun run() {
            updateMusicInfo()
            musicRefreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            setupFullScreen()

            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 应用壁纸背景
            applyWallpaper()

            updateTime()
            handler.post(updateTimeRunnable)

            requestPermissions()
            checkNotificationListenerPermission()
            loadCustomApps()
            setupAppGrid()
            setupBottomNavigation()
            setupNavButtons()
            startMusicRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 初始化失败", e)
            Toast.makeText(this, "初始化异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        setupFullScreen()
        loadCustomApps()
        gridAdapter.notifyDataSetChanged()
        // 刷新底部应用列表
        setupBottomAppsRecyclerView()
        // 刷新壁纸
        applyWallpaper()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullScreen()
    }

    /**
     * 全屏沉浸式显示
     */
    private fun setupFullScreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupFullScreen 失败", e)
        }
    }

    /**
     * 应用壁纸背景
     */
    private fun applyWallpaper() {
        val wallpaperDrawable = WallpaperManager.getWallpaperDrawable(this)
        val ivWallpaper = findViewById<ImageView>(R.id.iv_wallpaper)
        if (ivWallpaper != null) {
            if (wallpaperDrawable != null) {
                ivWallpaper.setImageDrawable(wallpaperDrawable)
            } else {
                // 默认壁纸
                ivWallpaper.setImageResource(R.drawable.wallpaper_1)
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * 检查通知监听权限
     */
    private fun checkNotificationListenerPermission() {
        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要通知权限")
                .setMessage("请允许本应用访问通知，以获取音乐播放信息")
                .setPositiveButton("去开启") { _, _ ->
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 检查通知监听是否已启用
     */
    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(packageName) == true
    }

    private fun updateTime() {
        val now = Calendar.getInstance()
        findViewById<TextView>(R.id.tv_time)?.text = timeFormat.format(now.time)
        findViewById<TextView>(R.id.tv_date)?.text = dateFormat.format(now.time)
    }

    /**
     * 设置应用网格（动态加载已安装应用）
     */
    private fun setupAppGrid() {
        val rvGrid = findViewById<RecyclerView>(R.id.rv_app_grid)
        // 改为水平布局，一行，左右滑动
        rvGrid?.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val gridApps = getGridApps().toMutableList()
        // 添加自定义应用到网格
        for (app in customApps) {
            val icon = try {
                packageManager.getApplicationIcon(app.packageName)
            } catch (e: Exception) {
                null
            }
            gridApps.add(GridApp(appName = app.appName, icon = icon, iconBg = R.drawable.bg_icon_blue) {
                openApp(app.packageName, app.appName)
            })
        }
        // 添加➕按钮项
        gridApps.add(GridApp(appName = "添加", iconRes = R.drawable.ic_add, iconBg = R.drawable.bg_icon_gray) {
            showAddAppDialog()
        })
        gridAdapter = AppGridAdapter(gridApps)
        rvGrid?.adapter = gridAdapter
    }

    /**
     * 获取网格显示的应用列表（常用应用）
     */
    private fun getGridApps(): List<GridApp> {
        val apps = mutableListOf<GridApp>()
        
        try {
            // 固定快捷入口
            apps.add(GridApp(appName = "应用管理", iconRes = R.drawable.ic_apps, iconBg = R.drawable.bg_icon_blue) {
                startActivity(Intent(this, AppManagerActivity::class.java))
            })
            apps.add(GridApp(appName = "系统设置", iconRes = R.drawable.ic_settings, iconBg = R.drawable.bg_icon_orange) {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
            apps.add(GridApp(appName = "主题中心", iconRes = R.drawable.ic_music, iconBg = R.drawable.bg_icon_cyan) {
                Toast.makeText(this, "主题中心开发中", Toast.LENGTH_SHORT).show()
            })

            // 动态检测音乐应用
            val musicApps = AppRecognizer.getInstalledMusicApps(this)
            if (musicApps.isNotEmpty()) {
                val musicApp = musicApps[0]
                apps.add(GridApp(appName = musicApp.appName, icon = musicApp.icon, iconBg = R.drawable.bg_icon_orange) {
                    openApp(musicApp.packageName, musicApp.appName)
                })
            }

            // 动态检测导航应用
            val navApps = AppRecognizer.getInstalledNavigationApps(this)
            if (navApps.isNotEmpty()) {
                val navApp = navApps[0]
                apps.add(GridApp(appName = navApp.appName, icon = navApp.icon, iconBg = R.drawable.bg_icon_green) {
                    openApp(navApp.packageName, navApp.appName)
                })
            }

            // 文件管理
            apps.add(GridApp(appName = "文件管理", iconRes = R.drawable.ic_file, iconBg = R.drawable.bg_icon_blue) {
                openFileManager()
            })
        } catch (e: Exception) {
            Log.e(TAG, "获取应用列表失败", e)
            // 至少返回固定入口
            apps.add(GridApp(appName = "应用管理", iconRes = R.drawable.ic_apps, iconBg = R.drawable.bg_icon_blue) {
                startActivity(Intent(this, AppManagerActivity::class.java))
            })
            apps.add(GridApp(appName = "系统设置", iconRes = R.drawable.ic_settings, iconBg = R.drawable.bg_icon_orange) {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
        }

        return apps
    }

    private fun setupBottomNavigation() {
        // 固定功能按钮
        findViewById<LinearLayout>(R.id.nav_home)?.setOnClickListener {
            // 首页按钮点击事件
        }
        findViewById<LinearLayout>(R.id.nav_navigation)?.setOnClickListener {
            openNavigation()
        }
        findViewById<LinearLayout>(R.id.nav_music)?.setOnClickListener {
            showMusicAppsDialog()
        }
        findViewById<LinearLayout>(R.id.nav_add)?.setOnClickListener {
            showAddAppDialog()
        }

        // 设置可滑动的底部应用列表
        setupBottomAppsRecyclerView()
    }

    /**
     * 设置导航和音乐按钮点击事件
     */
    private fun setupNavButtons() {
        // 导航切换
        findViewById<TextView>(R.id.nav_switch)?.setOnClickListener {
            showNavSwitchDialog()
        }
        // 打开导航
        findViewById<Button>(R.id.nav_open)?.setOnClickListener {
            openMapApp()
        }
        // 音乐控制
        findViewById<ImageView>(R.id.music_prev)?.setOnClickListener {
            sendMediaAction("prev")
        }
        findViewById<ImageView>(R.id.music_play)?.setOnClickListener {
            sendMediaAction("play_pause")
        }
        findViewById<ImageView>(R.id.music_next)?.setOnClickListener {
            sendMediaAction("next")
        }
    }

    /**
     * 显示导航切换对话框
     */
    private fun showNavSwitchDialog() {
        val items = arrayOf("高德地图", "百度地图", "腾讯地图")
        val currentIndex = when (currentNavType) {
            "baidu" -> 1
            "tencent" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("选择导航")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                currentNavType = when (which) {
                    1 -> "baidu"
                    2 -> "tencent"
                    else -> "amap"
                }
                val navName = when (currentNavType) {
                    "baidu" -> "百度"
                    "tencent" -> "腾讯"
                    else -> "高德"
                }
                findViewById<TextView>(R.id.nav_switch)?.text = "$navName ▼"
                findViewById<TextView>(R.id.nav_status)?.text = "${navName}导航运行中"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开地图应用
     */
    private fun openMapApp() {
        val packages = arrayOf("com.autonavi.amapauto", "com.autonavi.minimap", "com.baidu.BaiduMap")
        for (pkg in packages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "未找到地图应用", Toast.LENGTH_SHORT).show()
    }

    // ========== 音乐控制 ==========

    /**
     * 启动音乐刷新
     */
    private fun startMusicRefresh() {
        musicRefreshHandler.post(musicRefreshRunnable)
    }

    /**
     * 更新音乐信息
     */
    private fun updateMusicInfo() {
        val title = MusicNotificationListener.currentTitle
        val artist = MusicNotificationListener.currentArtist
        val isPlaying = MusicNotificationListener.isPlaying

        findViewById<TextView>(R.id.music_title)?.text = if (title.isNotEmpty()) title else "未在播放"
        findViewById<TextView>(R.id.music_artist)?.text = artist
        findViewById<ImageView>(R.id.music_play)?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /**
     * 发送媒体控制动作
     */
    private fun sendMediaAction(action: String) {
        val controller = MusicNotificationListener.activeMediaController
        if (controller != null) {
            when (action) {
                "prev" -> controller.transportControls?.skipToPrevious()
                "next" -> controller.transportControls?.skipToNext()
                "play_pause" -> {
                    if (MusicNotificationListener.isPlaying) {
                        controller.transportControls?.pause()
                    } else {
                        controller.transportControls?.play()
                    }
                }
            }
        } else {
            // 兼容方案：发送媒体按钮广播
            val pkg = MusicNotificationListener.currentPackageName
            if (pkg.isNotEmpty()) {
                try {
                    val intent = Intent("android.intent.action.MEDIA_BUTTON").apply {
                        putExtra("android.intent.extra.KEY_EVENT", when(action) {
                            "prev" -> android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                            "next" -> android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                            else -> android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, 
                                if (MusicNotificationListener.isPlaying) android.view.KeyEvent.KEYCODE_MEDIA_PAUSE 
                                else android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                        })
                        setPackage(pkg)
                    }
                    sendBroadcast(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun setupBottomAppsRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.rv_bottom_apps) ?: return
        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        
        val bottomApps = getBottomApps()
        val adapter = BottomAppsAdapter(bottomApps)
        recyclerView.adapter = adapter
    }
    
    private fun getBottomApps(): List<BottomApp> {
        val apps = mutableListOf<BottomApp>()
        
        // 添加固定功能：应用管理
        apps.add(BottomApp("应用管理", R.drawable.ic_apps, null) {
            startActivity(Intent(this, AppManagerActivity::class.java))
        })
        
        // 添加固定功能：文件管理
        apps.add(BottomApp("文件管理", R.drawable.ic_file, null) {
            openFileManager()
        })
        
        // 添加固定功能：音量
        apps.add(BottomApp("音量", R.drawable.ic_volume, null) {
            showVolumeDialog()
        })
        
        // 添加固定功能：设置
        apps.add(BottomApp("设置", R.drawable.ic_settings, null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        
        // 添加自定义应用
        for (app in customApps) {
            val icon = try {
                packageManager.getApplicationIcon(app.packageName)
            } catch (e: Exception) {
                null
            }
            apps.add(BottomApp(app.appName, 0, icon) {
                openApp(app.packageName, app.appName)
            })
        }
        
        return apps
    }

    // ========== 音乐播放器功能 ==========
    
    private var selectedMusicApp: String? = null
    private var musicAppsDialog: Dialog? = null

    /**
     * 显示音乐应用选择弹窗
     */
    private fun showMusicAppsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_music_apps, null)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rv_music_apps)
        rvApps?.layoutManager = LinearLayoutManager(this)
        
        // 加载已保存的选择
        selectedMusicApp = getSharedPreferences("music", Context.MODE_PRIVATE)
            .getString("selected_app", null)
        
        // 获取本机所有音乐应用
        val musicApps = getInstalledMusicAppsList()
        
        if (musicApps.isEmpty()) {
            Toast.makeText(this, "未检测到音乐应用", Toast.LENGTH_SHORT).show()
            return
        }
        
        val adapter = MusicAppsAdapter(this, musicApps, selectedMusicApp) { app ->
            selectedMusicApp = app.packageName
            // 保存选择
            getSharedPreferences("music", Context.MODE_PRIVATE).edit()
                .putString("selected_app", app.packageName).apply()
            // 启动音乐应用
            try {
                val intent = packageManager.getLaunchIntentForPackage(app.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {}
            musicAppsDialog?.dismiss()
        }
        rvApps?.adapter = adapter
        
        musicAppsDialog = Dialog(this, R.style.Theme_PandaCarLauncher_Dialog)
        musicAppsDialog?.setContentView(dialogView)
        musicAppsDialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        musicAppsDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        musicAppsDialog?.show()
    }

    /**
     * 获取已安装的音乐应用列表
     */
    private fun getInstalledMusicAppsList(): List<MusicAppsAdapter.MusicAppInfo> {
        val musicPackages = listOf(
            // QQ音乐
            "com.tencent.qqmusic",
            "com.tencent.qqmusic.car",
            "com.tencent.qqmusiclite",
            "com.tencent.qqmusic.iot",
            "com.tencent.qqmusic.vehicle",
            // 酷我音乐
            "cn.kuwo.player",
            "cn.kuwo.kwmusiccar",
            "cn.kuwo.car",
            "com.kuwo.player",
            "com.kuwo.vehicle",
            // 酷狗音乐
            "com.kugou.android",
            "com.kugou.android.lite",
            "com.kugou.player",
            // 网易云音乐
            "com.netease.cloudmusic",
            "com.netease.cloudmusic.car",
            // 汽水音乐
            "com.qishui.music",
            "com.qishui.music.tycx",
            // 波点音乐
            "com.dotpoints.bodian",
            // 咪咕音乐
            "cmccwm.mobilemusic",
            // Spotify
            "com.spotify.music",
            // Apple Music
            "com.apple.android.music",
            // 其他
            "com.jiongya.vehiclemusic","com.bytedance.byteautoservices",
            "com.musicplayer.android"
        )

        val apps = mutableListOf<MusicAppsAdapter.MusicAppInfo>()
        for (pkg in musicPackages) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val appName = appInfo.loadLabel(packageManager).toString()
                val icon = appInfo.loadIcon(packageManager)
                apps.add(MusicAppsAdapter.MusicAppInfo(pkg, appName, icon))
            } catch (_: Exception) {}
        }

        return apps
    }

    /**
     * 播放音乐
     */
    private fun playMusic() {
        val controller = MusicNotificationListener.activeMediaController
        controller?.transportControls?.play()
    }

    /**
     * 暂停音乐
     */
    private fun pauseMusic() {
        val controller = MusicNotificationListener.activeMediaController
        controller?.transportControls?.pause()
    }

    /**
     * 下一曲
     */
    private fun nextMusic() {
        val controller = MusicNotificationListener.activeMediaController
        controller?.transportControls?.skipToNext()
    }

    /**
     * 上一曲
     */
    private fun prevMusic() {
        val controller = MusicNotificationListener.activeMediaController
        controller?.transportControls?.skipToPrevious()
    }

    /**
     * 切换音乐应用
     */
    private fun showMusicSwitchDialog() {
        val musicApps = getInstalledMusicApps()
        if (musicApps.isEmpty()) {
            Toast.makeText(this, "未检测到音乐应用", Toast.LENGTH_SHORT).show()
            return
        }
        val names = musicApps.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择音乐应用")
            .setItems(names) { _, which ->
                val (pkg, name) = musicApps[which]
                openMusicApp(pkg)
            }
            .show()
    }

    private fun getInstalledMusicApps(): List<Pair<String, String>> {
        val apps = mutableListOf<Pair<String, String>>()
        val musicPackages = mapOf(
            "cn.kuwo.kuwomusiccar" to "酷我音乐",
"cn.kuwo.kuwomusiccarsnp" to "酷我vip",
            "com.tencent.qqmusic" to "QQ音乐",
            "com.netease.cloudmusic" to "网易云音乐",
            "com.kugou.android.auto" to "酷狗音乐"
        )
        for ((pkg, name) in musicPackages) {
            try {
                if (packageManager.getPackageInfo(pkg, 0) != null) {
                    apps.add(pkg to name)
                }
            } catch (_: Exception) {}
        }
        return apps
    }

    private fun openMusicApp(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp(packageName: String, appName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "$appName 无法启动", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开应用失败: $packageName", e)
            Toast.makeText(this, "打开 $appName 失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFileManager() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("content://com.android.externalstorage.documents"), "vnd.android.document/root")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开文件管理", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVolumeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_volume)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.BOTTOM)

        // 静音按钮
        val ivMute = dialog.findViewById<ImageView>(R.id.iv_mute)
        var isMuted = false
        val savedVolumes = mutableMapOf<Int, Int>()
        ivMute?.setOnClickListener {
            isMuted = !isMuted
            if (isMuted) {
                savedVolumes[AudioManager.STREAM_MUSIC] = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                savedVolumes[AudioManager.STREAM_RING] = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                savedVolumes[AudioManager.STREAM_ALARM] = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                safeSetVolume(AudioManager.STREAM_MUSIC, 0)
                safeSetVolume(AudioManager.STREAM_RING, 0)
                safeSetVolume(AudioManager.STREAM_ALARM, 0)
                ivMute.setImageResource(R.drawable.ic_volume_mute)
                updateVolumeUI(dialog, R.id.seek_media_volume, R.id.tv_media_volume, AudioManager.STREAM_MUSIC)
                updateVolumeUI(dialog, R.id.seek_ring_volume, R.id.tv_ring_volume, AudioManager.STREAM_RING)
                updateVolumeUI(dialog, R.id.seek_alarm_volume, R.id.tv_alarm_volume, AudioManager.STREAM_ALARM)
            } else {
                savedVolumes[AudioManager.STREAM_MUSIC]?.let { safeSetVolume(AudioManager.STREAM_MUSIC, it) }
                savedVolumes[AudioManager.STREAM_RING]?.let { safeSetVolume(AudioManager.STREAM_RING, it) }
                savedVolumes[AudioManager.STREAM_ALARM]?.let { safeSetVolume(AudioManager.STREAM_ALARM, it) }
                ivMute.setImageResource(R.drawable.ic_volume)
                updateVolumeUI(dialog, R.id.seek_media_volume, R.id.tv_media_volume, AudioManager.STREAM_MUSIC)
                updateVolumeUI(dialog, R.id.seek_ring_volume, R.id.tv_ring_volume, AudioManager.STREAM_RING)
                updateVolumeUI(dialog, R.id.seek_alarm_volume, R.id.tv_alarm_volume, AudioManager.STREAM_ALARM)
            }
        }

        setupVolumeControl(dialog, R.id.seek_media_volume, R.id.tv_media_volume, R.id.btn_media_minus, R.id.btn_media_plus, AudioManager.STREAM_MUSIC)
        setupVolumeControl(dialog, R.id.seek_ring_volume, R.id.tv_ring_volume, R.id.btn_ring_minus, R.id.btn_ring_plus, AudioManager.STREAM_RING)
        setupVolumeControl(dialog, R.id.seek_alarm_volume, R.id.tv_alarm_volume, R.id.btn_alarm_minus, R.id.btn_alarm_plus, AudioManager.STREAM_ALARM)

        dialog.show()
    }

    private fun safeSetVolume(streamType: Int, volume: Int) {
        try {
            audioManager.setStreamVolume(streamType, volume, 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "设置音量失败(权限不足): stream=$streamType", e)
        } catch (e: Exception) {
            Log.e(TAG, "设置音量失败: stream=$streamType", e)
        }
    }

    private fun updateVolumeUI(dialog: Dialog, seekId: Int, tvId: Int, streamType: Int) {
        val seek = dialog.findViewById<SeekBar>(seekId)
        val tv = dialog.findViewById<TextView>(tvId)
        seek?.progress = audioManager.getStreamVolume(streamType)
        tv?.text = "${audioManager.getStreamVolume(streamType)}"
    }

    private fun setupVolumeControl(dialog: Dialog, seekId: Int, tvId: Int, minusBtnId: Int, plusBtnId: Int, streamType: Int) {
        val seek = dialog.findViewById<SeekBar>(seekId)
        val tv = dialog.findViewById<TextView>(tvId)
        val max = audioManager.getStreamMaxVolume(streamType)
        seek?.max = max
        val current = audioManager.getStreamVolume(streamType)
        seek?.progress = current
        tv?.text = "$current"

        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    safeSetVolume(streamType, progress)
                    tv?.text = "$progress"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dialog.findViewById<View>(minusBtnId)?.setOnClickListener {
            val newVol = (audioManager.getStreamVolume(streamType) - 1).coerceAtLeast(0)
            safeSetVolume(streamType, newVol)
            seek?.progress = newVol
            tv?.text = "$newVol"
        }

        dialog.findViewById<View>(plusBtnId)?.setOnClickListener {
            val newVol = (audioManager.getStreamVolume(streamType) + 1).coerceAtMost(max)
            safeSetVolume(streamType, newVol)
            seek?.progress = newVol
            tv?.text = "$newVol"
        }
    }

    private fun showAddAppDialog() {
        if (customApps.size >= MAX_CUSTOM_APPS) {
            Toast.makeText(this, R.string.custom_app_max_reached, Toast.LENGTH_SHORT).show()
            return
        }
        val allApps = AppRecognizer.getAllInstalledApps(this)
        if (allApps.isEmpty()) { Toast.makeText(this, "未检测到已安装应用", Toast.LENGTH_SHORT).show(); return }
        val appNames = allApps.map { it.appName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_app_add_title)
            .setItems(appNames) { _, which ->
                val appInfo = allApps[which]
                customApps.add(CustomApp(appInfo.packageName, appInfo.appName))
                saveCustomApps()
                setupBottomAppsRecyclerView() // 刷新底部应用列表
                setupAppGrid() // 刷新应用网格
                Toast.makeText(this, "已添加: ${appInfo.appName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }



    private fun loadCustomApps() {
        customApps.clear()
        val json = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_CUSTOM_APPS, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); customApps.add(CustomApp(o.getString("packageName"), o.getString("appName"))) }
        } catch (e: Exception) { Log.e(TAG, "加载自定义应用失败", e) }
    }

    private fun saveCustomApps() {
        val arr = JSONArray()
        for (app in customApps) { val o = JSONObject(); o.put("packageName", app.packageName); o.put("appName", app.appName); arr.put(o) }
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM_APPS, arr.toString()).apply()
    }

    private fun showThemeCenterDialog() {
        val items = arrayOf("更换壁纸", "切换主题")
        AlertDialog.Builder(this)
            .setTitle("主题中心")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, WallpaperActivity::class.java))
                    1 -> showThemeSwitchDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showThemeSwitchDialog() {
        val themes = arrayOf("深色主题", "浅色主题")
        val current = if (ThemeManager.isDarkTheme(this)) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("切换主题")
            .setSingleChoiceItems(themes, current) { dialog, which ->
                val newTheme = if (which == 0) ThemeManager.THEME_DARK else ThemeManager.THEME_LIGHT
                ThemeManager.setTheme(this, newTheme)
                dialog.dismiss()
                Toast.makeText(this, "主题已切换", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimeRunnable)
        musicRefreshHandler.removeCallbacks(musicRefreshRunnable)
    }

    /**
     * 打开导航
     */
    private fun openNavigation() {
        val packages = listOf(
            // 高德
            "com.autonavi.amapauto" to "高德地图车机版",
            "com.autonavi.amapauto.chenmo" to "高德地图车机共存版",
            "com.autonavi.amapauto.u3d" to "高德地图车机共存U3D版",
            // 百度
            "com.baidu.BaiduMap" to "百度地图",
            "com.baidu.naviauto" to "百度地图车机版",
            // 腾讯
            "com.tencent.map" to "腾讯地图",
            // 搜狗
            "com.sogou.map.android" to "搜狗地图",
            // 美团
            "com.sankuai.meituan" to "美团"
        )

        for ((pkg, name) in packages) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    findViewById<TextView>(R.id.nav_status)?.text = "已启动: $name"
                    return
                }
            } catch (_: Exception) {}
        }

        Toast.makeText(this, "请安装导航应用", Toast.LENGTH_SHORT).show()
    }

    data class CustomApp(val packageName: String, val appName: String)

    /**
     * 底部应用数据
     */
    data class BottomApp(
        val appName: String,
        val iconRes: Int = 0,
        val icon: Drawable? = null,
        val onClick: () -> Unit
    )

    /**
     * 底部应用适配器
     */
    inner class BottomAppsAdapter(private val apps: List<BottomApp>) : RecyclerView.Adapter<BottomAppsAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
            val tvName: TextView = view.findViewById(R.id.tv_app_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_bottom_app, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.tvName.text = app.appName
            if (app.icon != null) {
                holder.ivIcon.setImageDrawable(app.icon)
            } else {
                holder.ivIcon.setImageResource(app.iconRes)
            }
            holder.itemView.setOnClickListener { app.onClick() }
            // 长按移除自定义应用
            holder.itemView.setOnLongClickListener {
                val customApp = customApps.find { it.appName == app.appName }
                if (customApp != null) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("移除应用")
                        .setMessage("确定要移除 \"${app.appName}\" 吗？")
                        .setPositiveButton("移除") { _, _ ->
                            customApps.remove(customApp)
                            saveCustomApps()
                            setupBottomAppsRecyclerView()
                            setupAppGrid() // 刷新应用网格
                            Toast.makeText(this@MainActivity, "已移除 ${app.appName}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                } else {
                    false
                }
            }
        }

        override fun getItemCount() = apps.size
    }

    /**
     * 网格应用数据
     */
    data class GridApp(
        val appName: String,
        val iconRes: Int = 0,
        val icon: Drawable? = null,
        val iconBg: Int = R.drawable.bg_icon_blue,
        val onClick: () -> Unit
    )

    /**
     * dp转px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * 应用网格适配器
     */
    inner class AppGridAdapter(private val apps: List<GridApp>) : RecyclerView.Adapter<AppGridAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
            val tvName: TextView = view.findViewById(R.id.tv_name)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app_grid, parent, false))
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.tvName.text = app.appName
            if (app.icon != null) {
                holder.ivIcon.setImageDrawable(app.icon)
                holder.ivIcon.setBackgroundResource(0)
            } else {
                holder.ivIcon.setImageResource(app.iconRes)
                holder.ivIcon.setBackgroundResource(app.iconBg)
                holder.ivIcon.setPadding(16, 16, 16, 16)
            }

            // 设置图标间距
            val params = holder.itemView.layoutParams as RecyclerView.LayoutParams
            params.width = dpToPx(40)  // 图标宽度
            params.height = dpToPx(40) // 图标高度
            params.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)) // 四周间距
            holder.itemView.layoutParams = params

            holder.itemView.setOnClickListener { app.onClick() }
        }
        override fun getItemCount() = apps.size
    }
}
