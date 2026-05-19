package com.pandora.carlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*

/**
 * 画中画地图服务
 * 
 * 方案：原生悬浮窗 + 专用地图 APP
 * - 定位/语音/离线/车道级 全部由地图官方APP实现
 * - 本服务只做「容器 + 控制」
 * 
 * 参考：布丁UI、氢桌面的画中画地图实现
 */
class FloatingMapService : Service() {

    companion object {
        private const val TAG = "FloatingMap"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_map"

        const val EXTRA_MAP_PACKAGE = "extra_map_package"
        const val EXTRA_MAP_NAME = "extra_map_name"

        // 地图APP包名映射
        val MAP_PACKAGES = mapOf(
            "amap" to listOf(
                "com.autonavi.amapauto",      // 高德车机版
                "com.autonavi.amapauto.chenmo", // 高德车机共存版
                "com.autonavi.minimap"        // 高德手机版
            ),
            "baidu" to listOf(
                "com.baidu.naviauto",         // 百度车机版
                "com.baidu.BaiduMap"          // 百度手机版
            ),
            "tencent" to listOf(
                "com.tencent.map"             // 腾讯地图
            )
        )

        // 地图APP主Activity映射
        val MAP_ACTIVITIES = mapOf(
            "com.autonavi.amapauto" to "com.autonavi.map.activity.SplashActivity",
            "com.autonavi.minimap" to "com.autonavi.map.activity.SplashActivity",
            "com.baidu.naviauto" to "com.baidu.navi.NaviActivity",
            "com.baidu.BaiduMap" to "com.baidu.mapapi.map.MapActivity",
            "com.tencent.map" to "com.tencent.map.MainActivity"
        )
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var activityViewContainer: FrameLayout? = null
    
    private var currentMapType = "amap"
    private var currentMapPackage: String? = null
    private var mapName: String? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        
        intent?.let {
            currentMapPackage = it.getStringExtra(EXTRA_MAP_PACKAGE)
            mapName = it.getStringExtra(EXTRA_MAP_NAME) ?: "导航"
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (floatingView == null) {
            createFloatingWindow()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        Log.d(TAG, "创建画中画地图窗口")
        
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_pip_map, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 默认大小（导航区域大小）
        val dm = resources.displayMetrics
        val width = (dm.widthPixels * 0.6).toInt()
        val height = (dm.heightPixels * 0.5).toInt()

        params = WindowManager.LayoutParams(
            width, height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - width) / 2
            y = (dm.heightPixels - height) / 3
        }

        try {
            windowManager?.addView(floatingView, params)
            Log.d(TAG, "悬浮窗已添加 ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "创建悬浮窗失败", e)
            stopSelf()
            return
        }

        activityViewContainer = floatingView?.findViewById(R.id.pip_container)
        setupButtons(floatingView!!)
        setupDrag()
        
        // 启动地图APP
        launchMapApp()
    }

    /**
     * 启动地图APP
     * 方案1：直接启动地图APP（画中画模式）
     * 方案2：使用ActivityView嵌入（需要Android 11+）
     */
    private fun launchMapApp() {
        // 查找已安装的地图APP
        val pkg = findInstalledMapPackage()
        if (pkg == null) {
            showError("未安装地图应用")
            return
        }

        currentMapPackage = pkg
        Log.d(TAG, "启动地图: $pkg")

        try {
            // 方案：启动地图APP到画中画模式
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // 尝试传递画中画参数（部分地图支持）
                intent.putExtra("pip_mode", true)
                intent.putExtra("mini_mode", true)
                startActivity(intent)
                
                updateStatus(getMapDisplayName(pkg))
            } else {
                showError("无法启动地图")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动地图失败", e)
            showError("启动失败: ${e.message}")
        }
    }

    /**
     * 查找已安装的地图APP包名
     */
    private fun findInstalledMapPackage(): String? {
        val packages = MAP_PACKAGES[currentMapType] ?: return null
        for (pkg in packages) {
            try {
                packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        // 尝试其他类型
        for ((_, pkgs) in MAP_PACKAGES) {
            for (pkg in pkgs) {
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    return pkg
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun getMapDisplayName(pkg: String): String {
        return when {
            pkg.contains("autonavi") -> "高德地图"
            pkg.contains("baidu") -> "百度地图"
            pkg.contains("tencent") -> "腾讯地图"
            else -> "导航"
        }
    }

    private fun updateStatus(status: String) {
        floatingView?.findViewById<TextView>(R.id.pip_status)?.text = status
    }

    private fun showError(msg: String) {
        floatingView?.findViewById<TextView>(R.id.pip_status)?.text = msg
        floatingView?.findViewById<View>(R.id.pip_error)?.visibility = View.VISIBLE
    }

    private fun setupDrag() {
        val dragHandle = floatingView?.findViewById<View>(R.id.pip_drag_handle)
        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX + (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        try { windowManager?.updateViewLayout(floatingView, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupButtons(rootView: View) {
        // 关闭
        rootView.findViewById<ImageView>(R.id.pip_close)?.setOnClickListener {
            stopSelf()
        }

        // 切换地图
        rootView.findViewById<TextView>(R.id.pip_switch)?.setOnClickListener {
            showMapSwitchDialog()
        }

        // 全屏（启动地图APP）
        rootView.findViewById<ImageView>(R.id.pip_fullscreen)?.setOnClickListener {
            currentMapPackage?.let { pkg ->
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    private fun showMapSwitchDialog() {
        // 这里可以弹出选择对话框，简化处理直接切换
        val types = listOf("amap", "baidu", "tencent")
        val idx = types.indexOf(currentMapType)
        currentMapType = types[(idx + 1) % types.size]
        launchMapApp()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "画中画地图", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("画中画地图")
                .setSmallIcon(R.drawable.ic_navigation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("画中画地图")
                .setSmallIcon(R.drawable.ic_navigation)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
