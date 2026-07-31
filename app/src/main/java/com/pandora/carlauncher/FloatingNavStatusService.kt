package com.pandora.carlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 悬浮导航状态服务
 *
 * 【功能】
 * 导航启动后，在桌面边缘显示一个小型悬浮状态按钮。
 * - 点击：切回地图APP
 * - 长按：关闭导航状态
 *
 * 【场景】
 * 当使用官方车机版地图（非悬浮修改版）时，
 * 返回桌面后地图不可见，此悬浮按钮提供快速切回入口。
 *
 * 【权限】
 * 仅使用 SYSTEM_ALERT_WINDOW（悬浮窗权限），无系统签名。
 */
class FloatingNavStatusService : Service() {

    companion object {
        private const val TAG = "NavStatusService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "nav_status"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var mapPackage: String = ""
    private var mapType: String = ""
    private var mapMode: String = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            mapPackage = it.getStringExtra("map_package") ?: ""
            mapType = it.getStringExtra("map_type") ?: ""
            mapMode = it.getStringExtra("map_mode") ?: ""
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // 如果已有悬浮窗，先移除
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }

        if (floatingView == null) {
            createFloatingButton()
        }

        return START_STICKY
    }

    /**
     * 创建悬浮状态按钮
     * 一个圆形导航图标，点击切回地图，长按关闭
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingButton() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，无法创建状态按钮")
            stopSelf()
            return
        }

        // 创建容器布局
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // 圆形背景
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC00CED1"))
            setStroke(2, Color.parseColor("#FF6B6B"))
        }

        // 图标
        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_navigation)
            setColorFilter(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }

        // 状态文字
        val statusText = TextView(this).apply {
            text = "导航"
            setTextColor(Color.WHITE)
            textSize = 8f
            gravity = Gravity.CENTER
        }

        container.background = bgDrawable

        val size = (56 * resources.displayMetrics.density).toInt()
        val iconSize = (32 * resources.displayMetrics.density).toInt()

        container.addView(iconView, LinearLayout.LayoutParams(iconSize, iconSize))
        container.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        floatingView = container

        // 设置 WindowManager 参数
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            size, size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = (resources.displayMetrics.heightPixels * 0.4).toInt()
        }

        try {
            windowManager?.addView(floatingView, params)
            Log.d(TAG, "悬浮状态按钮已创建")
        } catch (e: Exception) {
            Log.e(TAG, "创建悬浮状态按钮失败", e)
            stopSelf()
            return
        }

        // 设置触摸事件：点击切回地图，长按关闭
        var touchStartTime = 0L
        var isLongPress = false
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartTime = System.currentTimeMillis()
                        isLongPress = false
                        hasMoved = false
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (dx > 10 || dy > 10 || dx < -10 || dy < -10) {
                            hasMoved = true
                            params?.x = initialX + dx.toInt()
                            params?.y = initialY + dy.toInt()
                            try {
                                windowManager?.updateViewLayout(floatingView, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchStartTime
                        if (!hasMoved) {
                            if (duration > 800) {
                                // 长按：关闭导航状态
                                stopSelf()
                            } else {
                                // 点击：切回地图
                                switchToMap()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    /**
     * 切回地图APP
     */
    private fun switchToMap() {
        if (mapPackage.isEmpty()) return
        try {
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage(mapPackage)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            Log.d(TAG, "切回地图: $mapPackage")
        } catch (e: Exception) {
            Log.e(TAG, "切回地图失败", e)
            Toast.makeText(this, "切回地图失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildNotification(): Notification {
        val mapName = FloatingNavManager.getMapDisplayName(mapType)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("导航运行中")
                .setContentText("$mapName 正在后台运行，点击悬浮按钮切回")
                .setSmallIcon(R.drawable.ic_navigation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("导航运行中")
                .setContentText("$mapName 正在后台运行")
                .setSmallIcon(R.drawable.ic_navigation)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "导航状态",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
        Log.d(TAG, "悬浮状态服务已销毁")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
