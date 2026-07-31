package com.pandora.carlauncher

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * 悬浮导航管理器
 *
 * 【核心原理】
 * 支持两种导航模式：
 *   1. 悬浮叠加模式：依赖修改版悬浮地图APP，地图自身持有 SYSTEM_ALERT_WINDOW，
 *      拉起后延时返回桌面，地图悬浮窗叠加在桌面上层。
 *   2. 跳转返回模式：使用官方车机版地图（高德/百度/腾讯），
 *      拉起地图全屏运行，延时后自动返回桌面。
 *      桌面显示一个悬浮状态按钮，点击可快速切回地图。
 *
 * 【支持地图类型】
 *   - 高德地图（车机版 + 修改悬浮版 + 手机版）
 *   - 百度地图（车机版 + 修改悬浮版 + 手机版）
 *   - 腾讯地图（车机版 + 手机版）
 *
 * 【不做什么】
 *   - 不集成地图SDK / 不渲染地图画面
 *   - 不使用TaskView / ActivityView / 屏幕捕获
 *   - 不使用任何需要系统签名、root、注入、进程劫持的API
 *
 * 【目标设备】东风风神AX7马赫版 Windlink车机 (Android 9)
 */
object FloatingNavManager {

    private const val TAG = "FloatingNavManager"

    // ======================== SharedPreferences ========================

    private const val PREF_NAME = "floating_nav_prefs"
    private const val KEY_MAP_TYPE = "selected_map_type"
    private const val KEY_AUTO_RETURN = "auto_return_enabled"
    private const val KEY_RETURN_DELAY = "return_delay_ms"
    private const val KEY_FLOATING_WIDGET = "floating_widget_enabled"

    // ======================== 地图类型常量 ========================

    const val MAP_TYPE_AMAP = "amap"
    const val MAP_TYPE_BAIDU = "baidu"
    const val MAP_TYPE_TENCENT = "tencent"

    // ======================== 地图APP包名配置 ========================

    /**
     * 地图APP配置
     *
     * @param floatingPackages 修改版悬浮地图包名（支持悬浮窗叠加）
     * @param carPackages 官方车机版包名（全屏运行，不支持悬浮叠加）
     * @param phonePackages 手机版包名（全屏运行）
     */
    data class MapAppConfig(
        val floatingPackages: List<String>,
        val carPackages: List<String>,
        val phonePackages: List<String>
    )

    /**
     * 各地图类型的APP配置
     *
     * 查找优先级：悬浮修改版 > 车机版 > 手机版
     */
    private val MAP_CONFIGS = mapOf(
        MAP_TYPE_AMAP to MapAppConfig(
            floatingPackages = listOf(
                "com.autonavi.amapauto.floating",   // 悬浮高德修改版
                "com.autonavi.amapauto.overlay",    // 悬浮叠加版
                "com.autonavi.amapauto.chenmo",     // 共存版（部分支持悬浮）
                "com.autonavi.amapauto.u3d",        // U3D修改版
                "com.autonavi.amapauto.superv",     // 超级版修改
                "com.autonavi.amapauto.xf"          // 悬浮修改版
            ),
            carPackages = listOf(
                "com.autonavi.amapauto",            // 高德地图车机版（官方）
                "com.autonavi.amapauto.pad"        // 高德地图车机Pad版
            ),
            phonePackages = listOf(
                "com.autonavi.minimap"              // 高德地图手机版
            )
        ),
        MAP_TYPE_BAIDU to MapAppConfig(
            floatingPackages = listOf(
                "com.baidu.naviauto.floating",      // 悬浮百度修改版
                "com.baidu.naviauto.overlay",       // 悬浮叠加版
                "com.baidu.naviauto.superv",        // 超级版修改
                "com.baidu.naviauto.xf",            // 悬浮修改版
                "com.baidu.BaiduMap.floating"       // 手机版悬浮修改
            ),
            carPackages = listOf(
                "com.baidu.naviauto",               // 百度地图车机版（官方）
                "com.baidu.carlife"                 // 百度CarLife
            ),
            phonePackages = listOf(
                "com.baidu.BaiduMap",               // 百度地图手机版
                "com.baidu.map.location"            // 百度地图定位版
            )
        ),
        MAP_TYPE_TENCENT to MapAppConfig(
            floatingPackages = listOf(
                "com.tencent.map.floating",         // 悬浮腾讯修改版
                "com.tencent.map.overlay"           // 悬浮叠加版
            ),
            carPackages = listOf(
                "com.tencent.map",                   // 腾讯地图（官方）
                "com.tencent.map.car"               // 腾讯地图车机版
            ),
            phonePackages = listOf(
                "com.tencent.map.lite"              // 腾讯地图极速版
            )
        )
    )

    /**
     * 各地图类型的显示名称
     */
    private val MAP_DISPLAY_NAMES = mapOf(
        MAP_TYPE_AMAP to "高德地图",
        MAP_TYPE_BAIDU to "百度地图",
        MAP_TYPE_TENCENT to "腾讯地图"
    )

    /** 默认自动返回延时（毫秒） */
    private const val DEFAULT_RETURN_DELAY = 2500L

    // ======================== 配置读写 ========================

    fun getSelectedMapType(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MAP_TYPE, MAP_TYPE_AMAP) ?: MAP_TYPE_AMAP
    }

    fun setSelectedMapType(context: Context, mapType: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAP_TYPE, mapType).apply()
        Log.d(TAG, "地图类型已切换: $mapType")
    }

    fun isAutoReturnEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RETURN, true)
    }

    fun setAutoReturnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_RETURN, enabled).apply()
    }

    fun getReturnDelay(context: Context): Long {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_RETURN_DELAY, DEFAULT_RETURN_DELAY)
    }

    /**
     * 是否启用导航悬浮状态按钮
     * 开启后，导航运行期间桌面显示一个小悬浮按钮，点击可快速切回地图
     */
    fun isFloatingWidgetEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLOATING_WIDGET, true)
    }

    fun setFloatingWidgetEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLOATING_WIDGET, enabled).apply()
    }

    // ======================== 地图应用检测 ========================

    /**
     * 查找已安装的地图APP
     *
     * 查找优先级：悬浮修改版 > 车机版 > 手机版
     *
     * @return MapInstallResult 包含包名和模式
     */
    fun findInstalledMap(context: Context, mapType: String): MapInstallResult? {
        val config = MAP_CONFIGS[mapType] ?: return null
        val pm = context.packageManager

        // 1. 优先查找悬浮修改版
        for (pkg in config.floatingPackages) {
            if (isPackageInstalled(pm, pkg)) {
                Log.d(TAG, "找到悬浮版地图: $pkg ($mapType)")
                return MapInstallResult(pkg, MapMode.FLOATING, mapType)
            }
        }

        // 2. 查找车机版
        for (pkg in config.carPackages) {
            if (isPackageInstalled(pm, pkg)) {
                Log.d(TAG, "找到车机版地图: $pkg ($mapType)")
                return MapInstallResult(pkg, MapMode.FULLSCREEN, mapType)
            }
        }

        // 3. 查找手机版
        for (pkg in config.phonePackages) {
            if (isPackageInstalled(pm, pkg)) {
                Log.d(TAG, "找到手机版地图: $pkg ($mapType)")
                return MapInstallResult(pkg, MapMode.FULLSCREEN, mapType)
            }
        }

        return null
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检测指定类型是否安装了悬浮修改版
     */
    fun findInstalledFloatingMap(context: Context, mapType: String): String? {
        val config = MAP_CONFIGS[mapType] ?: return null
        val pm = context.packageManager
        for (pkg in config.floatingPackages) {
            if (isPackageInstalled(pm, pkg)) return pkg
        }
        return null
    }

    /**
     * 获取当前选中地图类型的已安装结果
     */
    fun getInstalledSelectedMap(context: Context): MapInstallResult? {
        return findInstalledMap(context, getSelectedMapType(context))
    }

    fun getMapDisplayName(mapType: String): String {
        return MAP_DISPLAY_NAMES[mapType] ?: "未知地图"
    }

    /**
     * 获取所有已安装的地图类型列表
     */
    fun getInstalledMaps(context: Context): List<MapInstallResult> {
        val results = mutableListOf<MapInstallResult>()
        for (mapType in MAP_CONFIGS.keys) {
            findInstalledMap(context, mapType)?.let { results.add(it) }
        }
        return results
    }

    /**
     * 获取所有支持的地图类型
     */
    fun getAllMapTypes(): List<String> {
        return MAP_CONFIGS.keys.toList()
    }

    // ======================== 权限校验 ========================

    fun hasOwnOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun hasAppOverlayPermission(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, appInfo.uid, packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, appInfo.uid, packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    fun requestOwnOverlayPermission(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "无法打开权限设置页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestAppOverlayPermission(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "请在应用详情中开启「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ======================== 导航启动核心逻辑 ========================

    /**
     * 启动悬浮导航
     *
     * 【完整流程】
     * 1. 检查桌面自身悬浮窗权限
     * 2. 查找已安装的地图APP（悬浮版 > 车机版 > 手机版）
     * 3. 拉起地图APP
     * 4. 延时后自动切回桌面
     * 5. 启动悬浮状态按钮服务（显示导航运行状态，可快速切回地图）
     *
     * @param activity 调用方 Activity
     * @return true=成功启动
     */
    fun launchFloatingNav(activity: Activity): Boolean {
        Log.d(TAG, "===== 开始启动导航 =====")

        // 步骤1：检查桌面悬浮窗权限
        if (!hasOwnOverlayPermission(activity)) {
            showPermissionGuide(activity, "桌面缺少悬浮窗权限",
                "PandaDesk 需要悬浮窗权限才能在导航期间保持桌面显示。\n\n" +
                    "请点击「去授权」，在系统设置中开启「显示在其他应用上层」。",
                null)
            return false
        }

        // 步骤2：获取选中地图类型
        val mapType = getSelectedMapType(activity)
        val mapDisplayName = getMapDisplayName(mapType)
        Log.d(TAG, "选中地图: $mapType ($mapDisplayName)")

        // 步骤3：查找已安装的地图APP
        val mapResult = findInstalledMap(activity, mapType)
        if (mapResult == null) {
            Log.w(TAG, "地图未安装: $mapType")
            showMapNotInstalledGuide(activity, mapType)
            return false
        }

        Log.d(TAG, "地图模式: ${mapResult.mode} (${mapResult.packageName})")

        // 步骤4：拉起地图APP
        val launched = launchMapApp(activity, mapResult.packageName)
        if (!launched) {
            Toast.makeText(activity, "启动 $mapDisplayName 失败", Toast.LENGTH_SHORT).show()
            return false
        }

        // 根据模式提示用户
        when (mapResult.mode) {
            MapMode.FLOATING -> {
                Toast.makeText(activity, "正在启动 $mapDisplayName（悬浮模式）...", Toast.LENGTH_SHORT).show()
            }
            MapMode.FULLSCREEN -> {
                Toast.makeText(activity, "正在启动 $mapDisplayName（全屏模式）...", Toast.LENGTH_SHORT).show()
            }
        }

        // 步骤5：延时后自动切回桌面
        if (isAutoReturnEnabled(activity)) {
            val delay = getReturnDelay(activity)
            Log.d(TAG, "将在 ${delay}ms 后返回桌面")
            Handler(Looper.getMainLooper()).postDelayed({
                returnToDesktop(activity)
                // 返回桌面后启动悬浮状态按钮
                if (isFloatingWidgetEnabled(activity)) {
                    startFloatingStatusService(activity, mapResult)
                }
            }, delay)
        } else {
            // 不自动返回，但也启动悬浮状态按钮（延迟启动）
            if (isFloatingWidgetEnabled(activity)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startFloatingStatusService(activity, mapResult)
                }, 1000)
            }
        }

        Log.d(TAG, "===== 导航启动完成 =====")
        return true
    }

    /**
     * 拉起地图APP
     */
    private fun launchMapApp(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra("floating_mode", true)
                intent.putExtra("overlay_mode", true)
                context.startActivity(intent)
                Log.d(TAG, "地图APP已拉起: $packageName")
                true
            } else {
                Log.e(TAG, "无法获取启动Intent: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉起地图APP异常: $packageName", e)
            false
        }
    }

    /**
     * 自动返回桌面
     */
    private fun returnToDesktop(context: Context) {
        try {
            Log.d(TAG, "自动返回桌面...")
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("from_floating_nav", true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "返回桌面失败", e)
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(homeIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Home Intent 也失败", e2)
            }
        }
    }

    /**
     * 启动悬浮状态按钮服务
     * 在桌面显示一个小悬浮按钮，点击可快速切回地图
     */
    private fun startFloatingStatusService(context: Context, mapResult: MapInstallResult) {
        try {
            val intent = Intent(context, FloatingNavStatusService::class.java).apply {
                putExtra("map_package", mapResult.packageName)
                putExtra("map_type", mapResult.mapType)
                putExtra("map_mode", mapResult.mode.name)
            }
            context.startService(intent)
            Log.d(TAG, "悬浮状态服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动悬浮状态服务失败", e)
        }
    }

    /**
     * 停止悬浮状态按钮服务
     */
    fun stopFloatingStatusService(context: Context) {
        try {
            context.stopService(Intent(context, FloatingNavStatusService::class.java))
            Log.d(TAG, "悬浮状态服务已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止悬浮状态服务失败", e)
        }
    }

    /**
     * 切回地图APP
     */
    fun switchToMap(context: Context) {
        val mapResult = getInstalledSelectedMap(context) ?: return
        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(mapResult.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
            Log.d(TAG, "切回地图: ${mapResult.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "切回地图失败", e)
            Toast.makeText(context, "切回地图失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 错误引导 ========================

    private fun showPermissionGuide(context: Context, title: String, message: String, targetPackage: String?) {
        if (context !is Activity) return
        android.app.AlertDialog.Builder(context)
            .setTitle(title).setMessage(message)
            .setPositiveButton("去授权") { _, _ ->
                if (targetPackage != null) requestAppOverlayPermission(context, targetPackage)
                else requestOwnOverlayPermission(context)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showMapNotInstalledGuide(context: Context, mapType: String) {
        if (context !is Activity) return
        val mapName = getMapDisplayName(mapType)
        android.app.AlertDialog.Builder(context)
            .setTitle("未安装 $mapName")
            .setMessage(
                "未检测到 $mapName。\n\n" +
                    "支持以下版本：\n" +
                    "• 修改版悬浮地图（推荐，支持悬浮叠加）\n" +
                    "• 官方车机版地图（全屏运行，可快速切回）\n" +
                    "• 手机版地图（全屏运行）\n\n" +
                    "请在车机应用市场搜索安装 $mapName。"
            )
            .setPositiveButton("知道了", null).show()
    }

    /**
     * 检查完整权限状态
     */
    fun checkPermissionStatus(context: Context): PermissionStatus {
        val mapType = getSelectedMapType(context)
        val ownPermission = hasOwnOverlayPermission(context)
        val mapResult = findInstalledMap(context, mapType)
        val mapInstalled = mapResult != null
        val mapPermission = if (mapResult != null) {
            hasAppOverlayPermission(context, mapResult.packageName)
        } else false

        return PermissionStatus(
            mapType = mapType,
            mapDisplayName = getMapDisplayName(mapType),
            ownOverlayPermission = ownPermission,
            mapInstalled = mapInstalled,
            mapPackage = mapResult?.packageName,
            mapMode = mapResult?.mode,
            mapOverlayPermission = mapPermission,
            autoReturnEnabled = isAutoReturnEnabled(context),
            floatingWidgetEnabled = isFloatingWidgetEnabled(context),
            ready = ownPermission && mapInstalled
        )
    }

    // ======================== 数据类 ========================

    /**
     * 地图安装检测结果
     *
     * @param packageName 包名
     * @param mode 模式：FLOATING（悬浮叠加）/ FULLSCREEN（全屏运行）
     * @param mapType 地图类型
     */
    data class MapInstallResult(
        val packageName: String,
        val mode: MapMode,
        val mapType: String
    )

    /**
     * 导航模式
     */
    enum class MapMode {
        /** 悬浮叠加模式：修改版地图支持悬浮窗，返回桌面后地图叠加显示 */
        FLOATING,
        /** 全屏模式：官方车机版/手机版，全屏运行，返回桌面后可快速切回 */
        FULLSCREEN
    }

    /**
     * 权限状态
     */
    data class PermissionStatus(
        val mapType: String,
        val mapDisplayName: String,
        val ownOverlayPermission: Boolean,
        val mapInstalled: Boolean,
        val mapPackage: String?,
        val mapMode: MapMode?,
        val mapOverlayPermission: Boolean,
        val autoReturnEnabled: Boolean,
        val floatingWidgetEnabled: Boolean,
        val ready: Boolean
    )
}
