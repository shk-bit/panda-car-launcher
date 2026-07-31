package com.pandora.carlauncher

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮导航设置页面
 *
 * 【功能列表】
 * 1. 单选选择悬浮地图类型（高德/百度）
 * 2. 权限状态实时检测与展示
 * 3. 权限授权跳转（桌面自身 + 第三方地图APP）
 * 4. 「启动导航自动返回桌面」开关
 * 5. 重要限制说明与 Windlink 适配说明
 *
 * 【UI规范】Dracula 深色主题，横屏车机布局，大触控控件
 *
 * 【设计参考】布丁UI、氢桌面设置页面风格
 */
class FloatingNavSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating_nav_settings)

        // 返回按钮
        findViewById<ImageView>(R.id.btn_back)?.setOnClickListener {
            finish()
        }

        // 初始化地图选择
        setupMapSelection()

        // 初始化权限检测
        setupPermissionCheck()

        // 初始化自动返回开关
        setupAutoReturnSwitch()
    }

    /**
     * 每次进入页面时自动刷新权限状态
     * （从系统授权页面返回后状态可能已变化）
     */
    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    // ======================== 地图选择 ========================

    /**
     * 设置地图类型单选
     *
     * 点击高德 → 保存 "amap" 到 SharedPreferences
     * 点击百度 → 保存 "baidu" 到 SharedPreferences
     */
    private fun setupMapSelection() {
        val currentType = FloatingNavManager.getSelectedMapType(this)

        // 高德地图选项
        val btnAmap = findViewById<View>(R.id.btn_select_amap)
        val ivAmapCheck = findViewById<ImageView>(R.id.iv_amap_check)
        val tvAmapStatus = findViewById<TextView>(R.id.tv_amap_status)

        // 百度地图选项
        val btnBaidu = findViewById<View>(R.id.btn_select_baidu)
        val ivBaiduCheck = findViewById<ImageView>(R.id.iv_baidu_check)
        val tvBaiduStatus = findViewById<TextView>(R.id.tv_baidu_status)

        // 更新选中状态
        fun updateSelection(mapType: String) {
            val isAmap = mapType == FloatingNavManager.MAP_TYPE_AMAP
            ivAmapCheck?.visibility = if (isAmap) View.VISIBLE else View.INVISIBLE
            ivBaiduCheck?.visibility = if (!isAmap) View.VISIBLE else View.INVISIBLE

            // 更新背景选中样式
            btnAmap?.background = getDrawable(
                if (isAmap) R.drawable.bg_map_btn_selected else R.drawable.bg_map_btn
            )
            btnBaidu?.background = getDrawable(
                if (!isAmap) R.drawable.bg_map_btn_selected else R.drawable.bg_map_btn
            )

            // 更新安装状态文字
            updateMapInstallStatus(tvAmapStatus, FloatingNavManager.MAP_TYPE_AMAP)
            updateMapInstallStatus(tvBaiduStatus, FloatingNavManager.MAP_TYPE_BAIDU)
        }

        // 初始化选中状态
        updateSelection(currentType)

        // 高德点击事件
        btnAmap?.setOnClickListener {
            FloatingNavManager.setSelectedMapType(this, FloatingNavManager.MAP_TYPE_AMAP)
            updateSelection(FloatingNavManager.MAP_TYPE_AMAP)
            Toast.makeText(this, "已选择悬浮高德地图", Toast.LENGTH_SHORT).show()
            // 切换后刷新权限状态
            refreshPermissionStatus()
        }

        // 百度点击事件
        btnBaidu?.setOnClickListener {
            FloatingNavManager.setSelectedMapType(this, FloatingNavManager.MAP_TYPE_BAIDU)
            updateSelection(FloatingNavManager.MAP_TYPE_BAIDU)
            Toast.makeText(this, "已选择悬浮百度地图", Toast.LENGTH_SHORT).show()
            // 切换后刷新权限状态
            refreshPermissionStatus()
        }
    }

    /**
     * 更新地图安装状态文字
     *
     * @param tvStatus 状态 TextView
     * @param mapType 地图类型
     */
    private fun updateMapInstallStatus(tvStatus: TextView?, mapType: String) {
        val installedPkg = FloatingNavManager.findInstalledFloatingMap(this, mapType)
        if (installedPkg != null) {
            tvStatus?.text = "已安装"
            tvStatus?.setTextColor(getColor(R.color.accent_green))
        } else {
            tvStatus?.text = "未检测到"
            tvStatus?.setTextColor(getColor(R.color.accent_red))
        }
    }

    // ======================== 权限检测 ========================

    /**
     * 设置权限检测区域
     *
     * - 桌面悬浮窗权限：显示状态 + 授权按钮
     * - 地图APP悬浮窗权限：显示状态 + 授权按钮
     * - 重新检测按钮
     */
    private fun setupPermissionCheck() {
        // 桌面悬浮窗权限 → 授权按钮
        findViewById<Button>(R.id.btn_own_permission)?.setOnClickListener {
            FloatingNavManager.requestOwnOverlayPermission(this)
        }

        // 地图APP悬浮窗权限 → 授权按钮
        findViewById<Button>(R.id.btn_map_permission)?.setOnClickListener {
            val mapPkg = FloatingNavManager.getInstalledSelectedMapPackage(this)
            if (mapPkg != null) {
                FloatingNavManager.requestAppOverlayPermission(this, mapPkg)
            } else {
                Toast.makeText(this, "请先安装修改版悬浮地图", Toast.LENGTH_SHORT).show()
            }
        }

        // 重新检测按钮
        findViewById<Button>(R.id.btn_recheck)?.setOnClickListener {
            refreshPermissionStatus()
            Toast.makeText(this, "已重新检测", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 刷新权限状态显示
     *
     * 调用 FloatingNavManager.checkPermissionStatus() 获取完整状态，
     * 更新 UI 上的文字和颜色。
     */
    private fun refreshPermissionStatus() {
        val status = FloatingNavManager.checkPermissionStatus(this)

        // —— 桌面悬浮窗权限 ——
        val tvOwnStatus = findViewById<TextView>(R.id.tv_own_permission_status)
        if (status.ownOverlayPermission) {
            tvOwnStatus?.text = "已授权"
            tvOwnStatus?.setTextColor(getColor(R.color.accent_green))
        } else {
            tvOwnStatus?.text = "未授权"
            tvOwnStatus?.setTextColor(getColor(R.color.accent_red))
        }

        // —— 地图APP悬浮窗权限 ——
        val tvMapStatus = findViewById<TextView>(R.id.tv_map_permission_status)
        val btnMapPermission = findViewById<Button>(R.id.btn_map_permission)
        if (!status.mapInstalled) {
            // 地图未安装
            tvMapStatus?.text = "未安装地图"
            tvMapStatus?.setTextColor(getColor(R.color.accent_orange))
            btnMapPermission?.isEnabled = false
        } else if (status.mapOverlayPermission) {
            // 已安装且已授权
            tvMapStatus?.text = "已授权"
            tvMapStatus?.setTextColor(getColor(R.color.accent_green))
            btnMapPermission?.isEnabled = true
        } else {
            // 已安装但未授权
            tvMapStatus?.text = "未授权"
            tvMapStatus?.setTextColor(getColor(R.color.accent_red))
            btnMapPermission?.isEnabled = true
        }

        // —— 更新地图安装状态文字 ——
        updateMapInstallStatus(
            findViewById(R.id.tv_amap_status),
            FloatingNavManager.MAP_TYPE_AMAP
        )
        updateMapInstallStatus(
            findViewById(R.id.tv_baidu_status),
            FloatingNavManager.MAP_TYPE_BAIDU
        )
    }

    // ======================== 自动返回开关 ========================

    /**
     * 设置「启动导航自动返回桌面」开关
     *
     * 状态持久化到 SharedPreferences
     */
    private fun setupAutoReturnSwitch() {
        val switch = findViewById<Switch>(R.id.switch_auto_return)
        switch?.isChecked = FloatingNavManager.isAutoReturnEnabled(this)

        switch?.setOnCheckedChangeListener { _, isChecked ->
            FloatingNavManager.setAutoReturnEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "已开启自动返回桌面" else "已关闭自动返回桌面",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
