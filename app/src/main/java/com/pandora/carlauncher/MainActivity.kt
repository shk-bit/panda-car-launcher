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
import android.app.ActivityManager
import android.hardware.display.DisplayManager
import android.view.KeyEvent
import android.view.MotionEvent
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
        const val MAX_CUSTOM_APPS = 10
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
        // 刷新底部应用列表
        setupBottomAppsRecyclerView()
        // 刷新壁纸
        applyWallpaper()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时重新加载布局
        recreate()
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
     * 设置应用网格（已移除，应用在底部导航栏显示）
     */
    private fun setupAppGrid() {
        // 应用网格功能已移除，应用显示在底部导航栏
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

        // 动态调整底部导航栏图标大小（绕过AutoSize适配）
        adjustBottomNavIconSize()

        // 设置可滑动的底部应用列表
        setupBottomAppsRecyclerView()
    }

    /**
     * 调整底部导航栏图标大小
     * 使用原始像素值绕过AutoSize适配，确保在不同屏幕上显示一致
     */
    private fun adjustBottomNavIconSize() {
        // 获取屏幕密度
        val density = resources.displayMetrics.density
        // 固定图标大小为 28dp 对应的像素值（不经过AutoSize转换）
        val iconSizePx = (28 * density).toInt()

        // 主页图标
        findViewById<LinearLayout>(R.id.nav_home)?.findViewById<ImageView>(android.R.id.content)?.let { }
        // 直接通过遍历子View来设置图标大小
        val navIds = listOf(R.id.nav_home, R.id.nav_navigation, R.id.nav_music, R.id.nav_add)
        for (navId in navIds) {
            findViewById<LinearLayout>(navId)?.let { navLayout ->
                for (i in 0 until navLayout.childCount) {
                    val child = navLayout.getChildAt(i)
                    if (child is ImageView) {
                        child.layoutParams.width = iconSizePx
                        child.layoutParams.height = iconSizePx
                    }
                }
            }
        }
    }

    /**
     * 设置导航和音乐按钮点击事件
     */
    private fun setupNavButtons() {
        // 导航切换（占位页上的）
        findViewById<TextView>(R.id.nav_switch)?.setOnClickListener {
            showNavSwitchDialog()
        }
        // 导航切换（SDK 工具栏上的）
        findViewById<TextView>(R.id.nav_switch2)?.setOnClickListener {
            showNavSwitchDialog()
        }
        // 关闭导航 SDK
        findViewById<ImageView>(R.id.nav_close)?.setOnClickListener {
            closeEmbeddedNav()
        }
        // 点击导航卡片/占位页 -> 启动嵌入式导航
        findViewById<View>(R.id.nav_placeholder)?.setOnClickListener {
            launchEmbeddedNav()
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
        // 音乐插件选择按钮
        findViewById<TextView>(R.id.music_plugin_switch)?.setOnClickListener {
            showMusicPluginSwitchDialog()
        }
    }

    /**
     * 显示导航切换对话框
     */
    private fun showNavSwitchDialog() {
        // 动态获取已安装的地图
        val installed = findInstalledMapBinding()
        val items = if (installed.isEmpty()) {
            arrayOf("高德地图(在线)", "百度地图(在线)", "腾讯地图(在线)")
        } else {
            installed.map { it.name }.toTypedArray()
        }

        val currentIndex = installed.indexOfFirst {
            it.type == currentNavType
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("选择导航")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                if (installed.isNotEmpty() && which < installed.size) {
                    val binding = installed[which]
                    currentNavType = binding.type
                    val navName = binding.name
                    findViewById<TextView>(R.id.nav_switch)?.text = "$navName ▼"

                    // 如果导航已打开，切换到新地图
                    if (embeddedNavType != null) {
                        loadEmbeddedNavWeb(binding.type, binding.name, binding.webUrl)
                    }
                } else {
                    // 在线版
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

                    if (embeddedNavType != null) {
                        val url = when (currentNavType) {
                            "baidu" -> "https://map.baidu.com/mobile/webapp/index/index"
                            "tencent" -> "https://map.qq.com/m/"
                            else -> "https://m.amap.com/navi/"
                        }
                        loadEmbeddedNavWeb(currentNavType!!, "$navName(在线)", url)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示音乐插件选择对话框
     */
    private fun showMusicPluginSwitchDialog() {
        val musicApps = getInstalledMusicAppsList()
        if (musicApps.isEmpty()) {
            Toast.makeText(this, "未安装音乐应用", Toast.LENGTH_SHORT).show()
            return
        }

        val items = musicApps.map { it.appName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择音乐应用")
            .setItems(items) { dialog, which ->
                val selectedApp = musicApps[which]
                // 启动选中的音乐应用
                openApp(selectedApp.packageName, selectedApp.appName)
                // 更新显示
                findViewById<TextView>(R.id.music_plugin_switch)?.text = "${selectedApp.appName.take(4)} ▼"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开地图应用
     */
    private fun openMapApp() {
        // 1. 首先尝试完整包名
        val packages = arrayOf(
            "com.autonavi.amapauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.baidu.map.location",
            "com.baidu.carlife",
            "com.tencent.map"
        )
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
        
        // 2. 前缀匹配：高德地图 (com.autonavi)
        val amapApps = findAppsByPrefix("com.autonavi", "高德")
        if (amapApps.isNotEmpty()) {
            openApp(amapApps[0].packageName, amapApps[0].appName)
            return
        }
        
        // 3. 前缀匹配：腾讯地图 (com.tencent)
        val tencentApps = findAppsByPrefix("com.tencent", "腾讯")
        if (tencentApps.isNotEmpty()) {
            openApp(tencentApps[0].packageName, tencentApps[0].appName)
            return
        }
        
        // 4. 前缀匹配：百度地图 (com.baidu)
        val baiduApps = findAppsByPrefix("com.baidu", "百度")
        if (baiduApps.isNotEmpty()) {
            openApp(baiduApps[0].packageName, baiduApps[0].appName)
            return
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
     * 多重备用方案确保控制成功
     */
    private fun sendMediaAction(action: String) {
        Log.d(TAG, "发送媒体动作: $action")
        
        // 方案1：使用 MediaController
        val controller = MusicNotificationListener.activeMediaController
        if (controller != null) {
            Log.d(TAG, "使用 MediaController 控制音乐")
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
            return
        }
        
        // 方案2：使用 AudioManager 发送媒体按钮事件
        Log.d(TAG, "使用 AudioManager 发送媒体按钮")
        val keyCode = when (action) {
            "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "play_pause" -> if (MusicNotificationListener.isPlaying) 
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE 
            else 
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        
        // 发送按键事件
        val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
        
        // 方案3：发送广播
        try {
            val intent = Intent("android.intent.action.MEDIA_BUTTON").apply {
                putExtra("android.intent.extra.KEY_EVENT", downEvent)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "发送媒体广播失败", e)
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
            // QQ音乐 - 全版本
            "com.tencent.qqmusic",
            "com.tencent.qqmusic.car",
            "com.tencent.qqmusiclite",
            "com.tencent.qqmusic.iot",
            "com.tencent.qqmusic.vehicle",
            "com.tencent.qqmusic.pad",
            "com.tencent.qqmusic.hd",
            "com.tencent.qqmusiccar",
            // QQ音乐共存版/修改版
            "com.tencent.qqmusic.mi",
            "com.tencent.qqmusic.vip",
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
            // 酷我音乐共存版
            "cn.kuwo.player.a",
            "cn.kuwo.player.b",
            "cn.kuwo.player.c",
            "cn.kuwo.kwmusic.a",
            "cn.kuwo.kwmusic.b",
            "cn.kuwo.kwmusic.c",
            // 酷狗音乐 - 全版本
            "com.kugou.android",
            "com.kugou.android.lite",
            "com.kugou.player",
            "com.kugou.android.auto",
            "com.kugou.android.car",
            "com.kugou.android.hd",
            "com.kugou.android.pad",
            // 酷狗音乐共存版
            "com.kugou.android.a",
            "com.kugou.android.b",
            "com.kugou.android.c",
            // 网易云音乐 - 全版本
            "com.netease.cloudmusic",
            "com.netease.cloudmusic.car",
            "com.netease.cloudmusic.lite",
            "com.netease.cloudmusic.hd",
            "com.netease.cloudmusic.auto",
            // 网易云音乐共存版
            "com.netease.cloudmusic.a",
            "com.netease.cloudmusic.b",
            "com.netease.cloudmusic.c",
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
            // 虾米音乐（已停运但可能还有用户）
            "com.xiami.music",
            // 喜马拉雅
            "com.ximalaya.ting.android",
            "com.ximalaya.ting.android.car",
            // 懒人听书
            "com.lrts.lots",
            // 番茄畅听
            "com.xs.fm",
            // 抖音音乐/汽水
            "com.bytedance.byteautoservices",
            "com.bytedance.byteautoservice3",
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
            // 其他音乐应用
            "com.luna.music"
        )

        val apps = mutableListOf<MusicAppsAdapter.MusicAppInfo>()
        
        // 1. 首先匹配完整包名
        for (pkg in musicPackages) {
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val appName = appInfo.loadLabel(packageManager).toString()
                val icon = appInfo.loadIcon(packageManager)
                apps.add(MusicAppsAdapter.MusicAppInfo(pkg, appName, icon))
            } catch (_: Exception) {}
        }
        
        // 2. 前缀匹配：酷我音乐 (cn.kuwo)
        apps.addAll(findAppsByPrefix("cn.kuwo", "酷我"))
        
        // 3. 前缀匹配：酷狗音乐 (com.kugou)
        apps.addAll(findAppsByPrefix("com.kugou", "酷狗"))
        
        // 4. 前缀匹配：QQ音乐 (com.tencent.qqmusic)
        apps.addAll(findAppsByPrefix("com.tencent.qqmusic", "QQ音乐"))
        
        // 5. 前缀匹配：抖音音乐 (com.bytedance)
        apps.addAll(findAppsByPrefix("com.bytedance", "抖音"))

        return apps.distinctBy { it.packageName }
    }
    
    /**
     * 根据前缀查找应用
     */
    private fun findAppsByPrefix(prefix: String, defaultType: String): List<MusicAppsAdapter.MusicAppInfo> {
        val apps = mutableListOf<MusicAppsAdapter.MusicAppInfo>()
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveList = packageManager.queryIntentActivities(intent, 0)
            
            // 空检查，防止返回 null 导致崩溃
            if (resolveList == null || resolveList.isEmpty()) {
                return apps
            }
            
            for (resolveInfo in resolveList) {
                try {
                    val pkg = resolveInfo.activityInfo?.packageName ?: continue
                    if (pkg.startsWith(prefix)) {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val appName = appInfo.loadLabel(packageManager).toString()
                        val icon = appInfo.loadIcon(packageManager)
                        apps.add(MusicAppsAdapter.MusicAppInfo(pkg, appName, icon))
                    }
                } catch (_: Exception) {
                    // 跳过无法获取信息的应用
                    continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找应用前缀 $prefix 失败: ${e.message}")
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

    // ========== 嵌入式导航 ==========

    /**
     * 主流车机地图绑定信息
     */
    data class MapBinding(
        val type: String,        // amap, baidu, tencent, sogou
        val name: String,        // 显示名称
        val packageName: String, // 包名
        val webUrl: String,       // WebView 内嵌 URL
        val className: String? = null  // 可选：直接启动的 Activity
    )

    /**
     * 主流车机地图绑定列表
     */
    private val mapBindings: List<MapBinding> = listOf(
        // 高德地图
        MapBinding("amap", "高德地图车机版", "com.autonavi.amapauto",
            "https://m.amap.com/navi/", "com.autonavi.map.activity.SplashActivity"),
        MapBinding("amap", "高德地图", "com.autonavi.minimap",
            "https://m.amap.com/navi/"),
        MapBinding("amap", "高德地图车机共存版", "com.autonavi.amapauto.chenmo",
            "https://m.amap.com/navi/"),
        MapBinding("amap", "高德地图U3D版", "com.autonavi.amapauto.u3d",
            "https://m.amap.com/navi/"),
        // 百度地图
        MapBinding("baidu", "百度地图车机版", "com.baidu.naviauto",
            "https://map.baidu.com/mobile/webapp/index/index"),
        MapBinding("baidu", "百度地图", "com.baidu.BaiduMap",
            "https://map.baidu.com/mobile/webapp/index/index"),
        MapBinding("baidu", "百度CarLife", "com.baidu.carlife",
            "https://map.baidu.com/mobile/webapp/index/index"),
        // 腾讯地图
        MapBinding("tencent", "腾讯地图", "com.tencent.map",
            "https://map.qq.com/m/"),
        // 搜狗地图
        MapBinding("sogou", "搜狗地图", "com.sogou.map.android",
            "https://map.sogou.com/"),
        // 美团
        MapBinding("meituan", "美团", "com.sankuai.meituan",
            "https://i.meituan.com/"),
        // Google Maps
        MapBinding("google", "Google Maps", "com.google.android.apps.maps",
            "https://www.google.com/maps")
    )

    /** 当前嵌入的地图类型 */
    private var embeddedNavType: String? = null

    /** 当前嵌入的地图包名 */
    private var embeddedNavPkg: String? = null

    /**
     * 打开导航（底部导航栏按钮）- 嵌入式
     */
    private fun openNavigation() {
        launchEmbeddedNav()
    }

    /**
     * 启动嵌入式导航（系统级方案）
     * 使用反射创建 CarActivityView 嵌入地图APP到导航区域
     * 参考：AOSP CarLauncher
     * 
     * 需要：系统签名 + ACTIVITY_EMBEDDING 权限
     * 降级：非系统环境自动回退到直接启动地图APP
     */
    private var activityViewInstance: Any? = null

    private fun launchEmbeddedNav() {
        val container = findViewById<FrameLayout>(R.id.nav_activity_container)
        if (container == null) {
            // 容器不存在，降级
            launchMapFallback()
            return
        }

        try {
            // 反射创建 CarActivityView
            val activityView = createCarActivityView(container)
            if (activityView != null) {
                activityViewInstance = activityView
                setupActivityViewCallback(activityView)
                
                // 切换显示
                findViewById<View>(R.id.nav_placeholder)?.visibility = View.GONE
                findViewById<View>(R.id.nav_sdk_container)?.visibility = View.VISIBLE
                findViewById<View>(R.id.nav_loading)?.visibility = View.GONE
                
                Log.d(TAG, "CarActivityView 创建成功，等待就绪")
            } else {
                launchMapFallback()
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 CarActivityView 失败，降级为直接启动", e)
            launchMapFallback()
        }
    }

    /**
     * 反射创建 CarActivityView
     * 类路径：android.car.app.CarActivityView
     */
    private fun createCarActivityView(container: FrameLayout): Any? {
        return try {
            val clazz = Class.forName("android.car.app.CarActivityView")
            val constructor = clazz.getConstructor(android.content.Context::class.java)
            val view = constructor.newInstance(this) as? View
            if (view != null) {
                container.removeAllViews()
                container.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            view
        } catch (e: Exception) {
            Log.e(TAG, "反射创建 CarActivityView 失败", e)
            null
        }
    }

    /**
     * 反射设置 CarActivityView 的 StateCallback
     */
    private fun setupActivityViewCallback(activityView: Any) {
        try {
            val clazz = activityView.javaClass
            // 创建 StateCallback 的匿名实现
            val callbackClass = Class.forName("android.car.app.CarActivityView\$StateCallback")
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onActivityViewReady" -> {
                        Log.d(TAG, "ActivityView ready, 启动地图")
                        try {
                            val view = args?.get(0) as? View
                            // 反射调用 startActivity
                            val startMethod = clazz.getMethod(
                                "startActivity",
                                android.content.Intent::class.java
                            )
                            val intent = Intent.makeMainSelectorActivity(
                                Intent.ACTION_MAIN,
                                Intent.CATEGORY_APP_MAPS
                            )
                            startMethod.invoke(activityView, intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "启动地图失败", e)
                            launchMapFallback()
                        }
                        null
                    }
                    "onActivityViewDestroyed" -> {
                        Log.d(TAG, "ActivityView destroyed")
                        null
                    }
                    "onTaskMovedToFront" -> {
                        val taskId = args?.get(0) as? Int ?: 0
                        Log.d(TAG, "Task moved to front: $taskId")
                        try {
                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            am.moveTaskToFront(this@MainActivity.taskId, 0)
                        } catch (e: Exception) {
                            Log.w(TAG, "移动Launcher到前台失败", e)
                        }
                        null
                    }
                    else -> null
                }
            }
            // 反射调用 setCallback
            val setCallbackMethod = clazz.getMethod("setCallback", callbackClass)
            setCallbackMethod.invoke(activityView, callback)
        } catch (e: Exception) {
            Log.e(TAG, "设置 ActivityView 回调失败", e)
        }
    }

    /**
     * 降级方案：直接启动地图APP
     */
    private fun launchMapFallback() {
        val installed = findInstalledMapBinding()
        if (installed.isNotEmpty()) {
            val target = installed.first()
            launchMapAppPip(target.packageName, target.name)
        } else {
            Toast.makeText(this, "未检测到地图应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 启动地图APP（画中画模式）
     * 定位/语音/离线/车道级 全部由地图官方APP实现
     */
    private fun launchMapAppPip(packageName: String, name: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 尝试传递画中画/迷你模式参数（部分地图支持）
                intent.putExtra("pip_mode", true)
                intent.putExtra("mini_mode", true)
                startActivity(intent)
                
                // 更新状态显示
                findViewById<TextView>(R.id.nav_status)?.text = "已启动: $name"
                Toast.makeText(this, "已启动$name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "无法启动$name", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 查找已安装的地图绑定
     */
    private fun findInstalledMapBinding(): List<MapBinding> {
        return mapBindings.filter { binding ->
            try {
                packageManager.getPackageInfo(binding.packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 加载嵌入式导航 WebView
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun loadEmbeddedNavWeb(type: String, name: String, url: String) {
        embeddedNavType = type
        embeddedNavPkg = mapBindings.find { it.type == type }?.packageName

        // 切换显示
        findViewById<View>(R.id.nav_placeholder)?.visibility = View.GONE
        findViewById<View>(R.id.nav_sdk_container)?.visibility = View.VISIBLE
        findViewById<View>(R.id.nav_loading)?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.nav_sdk_title)?.text = name
        findViewById<TextView>(R.id.nav_loading_text)?.text = "正在加载${name}..."

        // 配置 WebView
        val webView = findViewById<android.webkit.WebView>(R.id.nav_webview) ?: return
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.SINGLE_COLUMN
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, urlStr: String?) {
                findViewById<View>(R.id.nav_loading)?.visibility = View.GONE
            }
            override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                findViewById<TextView>(R.id.nav_loading_text)?.text = "加载失败，点击重试"
            }
        }

        webView.webChromeClient = android.webkit.WebChromeClient()
        webView.loadUrl(url)
    }

    /**
     * 关闭嵌入式导航，回到占位页
     */
    private fun closeEmbeddedNav() {
        val webView = findViewById<android.webkit.WebView>(R.id.nav_webview)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
        }
        findViewById<View>(R.id.nav_sdk_container)?.visibility = View.GONE
        findViewById<View>(R.id.nav_placeholder)?.visibility = View.VISIBLE
        embeddedNavType = null
        embeddedNavPkg = null
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
