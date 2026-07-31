package com.pandora.carlauncher

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 系统设置页面
 * 包含设备存储信息、悬浮球开关、各类系统设置跳转
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 返回按钮
        findViewById<ImageView>(R.id.btn_back)?.setOnClickListener {
            finish()
        }

        // 加载存储信息
        loadStorageInfo()

        // 悬浮球开关
        setupFloatBallSwitch()

        // 设置列表项点击事件
        setupSettingItems()
    }

    /**
     * 通过 StatFs 获取并显示设备存储信息
     */
    private fun loadStorageInfo() {
        try {
            val statFs = StatFs(android.os.Environment.getDataDirectory().path)
            val totalBytes = statFs.totalBytes
            val availableBytes = statFs.availableBytes
            val usedBytes = totalBytes - availableBytes

            val tvTotal = findViewById<TextView>(R.id.tv_total_storage)
            val tvUsed = findViewById<TextView>(R.id.tv_used_storage)
            val tvAvailable = findViewById<TextView>(R.id.tv_available_storage)
            val progressBar = findViewById<ProgressBar>(R.id.storage_progress)

            tvTotal?.text = formatFileSize(totalBytes)
            tvUsed?.text = formatFileSize(usedBytes)
            tvAvailable?.text = formatFileSize(availableBytes)

            val usedPercent = if (totalBytes > 0) {
                (usedBytes * 100 / totalBytes).toInt()
            } else {
                0
            }
            progressBar?.progress = usedPercent
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.1f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups.coerceAtMost(units.size - 1)]
        )
    }

    /**
     * 设置悬浮球开关
     */
    private fun setupFloatBallSwitch() {
        val switchFloatBall = findViewById<Switch>(R.id.switch_float_ball)
        val prefs = getSharedPreferences(PREF_FLOAT_BALL, MODE_PRIVATE)
        switchFloatBall?.isChecked = prefs.getBoolean(KEY_FLOAT_BALL_ENABLED, false)

        switchFloatBall?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_FLOAT_BALL_ENABLED, isChecked).apply()
            
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
                    switchFloatBall.isChecked = false
                    return@setOnCheckedChangeListener
                }
                startService(Intent(this, FloatingBallService::class.java))
                Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show()
            } else {
                stopService(Intent(this, FloatingBallService::class.java))
                Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 设置各列表项的点击事件
     */
    private fun setupSettingItems() {
        // 悬浮球设置
        findViewById<View>(R.id.item_float_ball_setting)?.setOnClickListener {
            startActivity(Intent(this, FloatingBallSettingsActivity::class.java))
        }
        bindSettingItem(
            R.id.item_float_ball_setting,
            R.drawable.ic_settings,
            "悬浮球设置",
            "自定义悬浮球样式和功能"
        )

        // 悬浮导航设置
        findViewById<View>(R.id.item_floating_nav)?.setOnClickListener {
            startActivity(Intent(this, FloatingNavSettingsActivity::class.java))
        }
        bindSettingItem(
            R.id.item_floating_nav,
            R.drawable.ic_navigation,
            "悬浮导航设置",
            "悬浮地图选择、权限检测、自动返回"
        )

        // 视频播放器
        findViewById<View>(R.id.item_video_player)?.setOnClickListener {
            // 打开文件选择器
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/3gp", "video/mkv", "video/avi"))
            }
            try {
                startActivityForResult(intent, 1003)
            } catch (e: Exception) {
                // 备选：直接打开播放器
                startActivity(Intent(this, VideoPlayerActivity::class.java))
            }
        }
        bindSettingItem(
            R.id.item_video_player,
            R.drawable.ic_play,
            "视频播放器",
            "选择并播放本地视频"
        )

        // WiFi设置
        findViewById<View>(R.id.item_wifi)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_WIFI_SETTINGS)
        }
        bindSettingItem(
            R.id.item_wifi,
            R.drawable.ic_settings,
            "WiFi设置",
            "管理无线网络连接"
        )

        // 蓝牙设置
        findViewById<View>(R.id.item_bluetooth)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        bindSettingItem(
            R.id.item_bluetooth,
            R.drawable.ic_settings,
            "蓝牙设置",
            "管理蓝牙设备和连接"
        )

        // 显示设置
        findViewById<View>(R.id.item_display)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS)
        }
        bindSettingItem(
            R.id.item_display,
            R.drawable.ic_settings,
            "显示设置",
            "亮度、壁纸、字体大小"
        )

        // 声音设置
        findViewById<View>(R.id.item_sound)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
        }
        bindSettingItem(
            R.id.item_sound,
            R.drawable.ic_volume,
            "声音设置",
            "音量、铃声、通知音"
        )

        // 应用管理
        findViewById<View>(R.id.item_app_manager)?.setOnClickListener {
            startActivity(Intent(this, AppManagerActivity::class.java))
        }
        bindSettingItem(
            R.id.item_app_manager,
            R.drawable.ic_apps,
            "应用管理",
            "查看和管理已安装应用"
        )

        // 存储设置
        findViewById<View>(R.id.item_storage)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }
        bindSettingItem(
            R.id.item_storage,
            R.drawable.ic_file,
            "存储设置",
            "管理内部存储空间"
        )

        // 位置设置
        findViewById<View>(R.id.item_location)?.setOnClickListener {
            openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        bindSettingItem(
            R.id.item_location,
            R.drawable.ic_navigation,
            "位置设置",
            "GPS定位和位置服务"
        )

        // 主题切换
        findViewById<View>(R.id.item_theme)?.setOnClickListener {
            showThemeDialog()
        }
        val currentTheme = if (ThemeManager.isDarkTheme(this)) "深色主题" else "浅色主题"
        bindSettingItem(
            R.id.item_theme,
            R.drawable.ic_settings,
            "主题切换",
            "当前: $currentTheme"
        )

        // 壁纸设置
        findViewById<View>(R.id.item_wallpaper)?.setOnClickListener {
            startActivity(Intent(this, WallpaperActivity::class.java))
        }
        bindSettingItem(R.id.item_wallpaper, R.drawable.ic_image, "壁纸设置", "更换桌面壁纸")

        // 蓝牙音乐
        findViewById<View>(R.id.item_bluetooth_music)?.setOnClickListener {
            startActivity(Intent(this, BluetoothMusicActivity::class.java))
        }
        bindSettingItem(R.id.item_bluetooth_music, R.drawable.ic_bluetooth, "蓝牙音乐", "蓝牙播放音乐")

        // 文件管理器
        findViewById<View>(R.id.item_file_manager)?.setOnClickListener {
            startActivity(Intent(this, FileManagerActivity::class.java))
        }
        bindSettingItem(R.id.item_file_manager, R.drawable.ic_folder, "文件管理器", "浏览和管理文件")

        // 小组件
        findViewById<View>(R.id.item_widgets)?.setOnClickListener {
            startActivity(Intent(this, WidgetsActivity::class.java))
        }
        bindSettingItem(R.id.item_widgets, R.drawable.ic_apps, "小组件", "时钟、天气、日历")

        // 应用市场
        findViewById<View>(R.id.item_app_market)?.setOnClickListener {
            startActivity(Intent(this, AppMarketActivity::class.java))
        }
        bindSettingItem(R.id.item_app_market, R.drawable.ic_apps, "应用市场", "下载更多应用")

        // 手机投屏
        findViewById<View>(R.id.item_screen_cast)?.setOnClickListener {
            startActivity(Intent(this, ScreenCastActivity::class.java))
        }
        bindSettingItem(R.id.item_screen_cast, R.drawable.ic_cast, "手机投屏", "Android Auto / CarPlay")

        // 一键清理
        findViewById<View>(R.id.item_clean_background)?.setOnClickListener {
            startActivity(Intent(this, CleanBackgroundActivity::class.java))
        }
        bindSettingItem(R.id.item_clean_background, R.drawable.ic_apps, "一键清理", "清理后台进程")

        // 画中画模式
        findViewById<View>(R.id.item_pip)?.setOnClickListener {
            showPipDialog()
        }
        bindSettingItem(R.id.item_pip, R.drawable.ic_apps, "画中画模式", "悬浮窗口播放视频")

        // 关于我们
        findViewById<View>(R.id.item_about)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        bindSettingItem(
            R.id.item_about,
            R.drawable.ic_apps,
            "关于我们",
            "版本信息和软件说明"
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1003 && resultCode == RESULT_OK && data?.data != null) {
            // 打开视频播放器
            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, data.data.toString())
                putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, "本地视频")
            }
            startActivity(intent)
        }
    }

    private fun showThemeDialog() {
        val themes = arrayOf("深色主题", "浅色主题")
        val current = if (ThemeManager.isDarkTheme(this)) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("选择主题")
            .setSingleChoiceItems(themes, current) { dialog, which ->
                val newTheme = if (which == 0) ThemeManager.THEME_DARK else ThemeManager.THEME_LIGHT
                ThemeManager.setTheme(this, newTheme)
                dialog.dismiss()
                // 重启应用以应用主题
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPipDialog() {
        val videoApps = AppRecognizer.getInstalledVideoApps(this)
        if (videoApps.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("画中画模式")
                .setMessage("未检测到视频应用。\n\n画中画模式需要视频应用支持，请安装以下应用之一：\n• 腾讯视频\n• 爱奇艺\n• 优酷\n• 哔哩哔哩\n• 抖音")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val names = videoApps.map { it.appName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择视频应用")
            .setItems(names) { _, which ->
                val app = videoApps[which]
                // 启动画中画模式
                val intent = Intent(this, PipActivity::class.java).apply {
                    putExtra(PipActivity.EXTRA_PACKAGE_NAME, app.packageName)
                    putExtra(PipActivity.EXTRA_APP_NAME, app.appName)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 绑定设置项的图标、标题、副标题
     */
    private fun bindSettingItem(
        itemId: Int,
        iconRes: Int,
        title: String,
        subtitle: String
    ) {
        val itemView = findViewById<View>(itemId)
        itemView?.findViewById<ImageView>(R.id.iv_setting_icon)?.setImageResource(iconRes)
        itemView?.findViewById<TextView>(R.id.tv_setting_title)?.text = title
        val tvSubtitle = itemView?.findViewById<TextView>(R.id.tv_setting_subtitle)
        if (tvSubtitle != null) {
            tvSubtitle.text = subtitle
            tvSubtitle.visibility = View.VISIBLE
        }
    }

    /**
     * 打开系统设置页面
     */
    private fun openSystemSettings(action: String) {
        try {
            val intent = Intent(action)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREF_FLOAT_BALL = "float_ball_pref"
        private const val KEY_FLOAT_BALL_ENABLED = "float_ball_enabled"
        private const val KEY_FLOAT_BALL_COLOR = "float_ball_color"
        private const val KEY_FLOAT_BALL_SIZE = "float_ball_size"
        private const val KEY_FLOAT_BALL_ALPHA = "float_ball_alpha"
        private const val KEY_FLOAT_BALL_FUNC_HOME = "float_ball_func_home"
        private const val KEY_FLOAT_BALL_FUNC_BACK = "float_ball_func_back"
        private const val KEY_FLOAT_BALL_FUNC_RECENT = "float_ball_func_recent"
        private const val KEY_FLOAT_BALL_FUNC_MUSIC = "float_ball_func_music"
        private const val KEY_FLOAT_BALL_FUNC_NAV = "float_ball_func_nav"
    }
}
