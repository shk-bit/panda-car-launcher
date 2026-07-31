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
 * 依赖民间修改版【悬浮高德地图车机版 / 悬浮百度地图车机版】，
 * 地图APP自身持有 SYSTEM_ALERT_WINDOW 悬浮窗能力。
 * PandaDesk 仅做：
 *   1. 权限校验（桌面自身 + 第三方地图APP）
 *   2. 应用调度（拉起地图APP → 延时返回桌面）
 *   3. 配置管理（SharedPreferences 持久化）
 *
 * 【不做什么】
 *   - 不集成地图SDK
 *   - 不渲染地图画面
 *   - 不使用TaskView / ActivityView / 屏幕捕获
 *   - 不使用任何需要系统签名、root、注入、进程劫持的API
 *
 * 【目标设备】东风风神AX7马赫版 Windlink车机 (Android 9)
 * 【应用权限】第三方普通应用，无系统签名
 *
 * 【参考产品】布丁UI、氢桌面、嘟嘟桌面 悬浮导航功能
 */
object FloatingNavManager {

    private const val TAG = "FloatingNavManager"

    // ======================== SharedPreferences 键名 ========================

    /** SharedPreferences 文件名 */
    private const val PREF_NAME = "floating_nav_prefs"

    /** 选中的地图类型： "amap"（悬浮高德） / "baidu"（悬浮百度） */
    private const val KEY_MAP_TYPE = "selected_map_type"

    /** 是否在启动导航后自动返回桌面 */
    private const val KEY_AUTO_RETURN = "auto_return_enabled"

    /** 自动返回桌面的延时时间（毫秒） */
    private const val KEY_RETURN_DELAY = "return_delay_ms"

    // ======================== 地图类型常量 ========================

    /** 悬浮高德地图 */
    const val MAP_TYPE_AMAP = "amap"

    /** 悬浮百度地图 */
    const val MAP_TYPE_BAIDU = "baidu"

    // ======================== 修改版悬浮地图APP包名 ========================

    /**
     * 悬浮高德地图车机版 - 已知修改版包名列表
     *
     * 注意：民间修改版包名可能因版本而异，此处列出常见包名。
     * 官方原版高德包名为 com.autonavi.amapauto，不支持悬浮窗叠加。
     * 用户需安装修改版（悬浮版），修改版包名通常带后缀或完全不同。
     */
    private val FLOATING_AMAP_PACKAGES = listOf(
        "com.autonavi.amapauto.floating",     // 悬浮高德修改版
        "com.autonavi.amapauto.overlay",      // 悬浮叠加版
        "com.autonavi.amapauto.chenmo",       // 高德车机共存版（部分支持悬浮）
        "com.autonavi.amapauto.u3d",          // U3D修改版（部分支持悬浮）
        "com.autonavi.amapauto.superv",      // 超级版修改
        "com.autonavi.amapauto.xf",           // 悬浮修改版
        // 如有其他修改版包名，可在此追加
    )

    /**
     * 悬浮百度地图车机版 - 已知修改版包名列表
     *
     * 官方原版百度地图包名为 com.baidu.naviauto，不支持悬浮窗叠加。
     * 用户需安装修改版（悬浮版）。
     */
    private val FLOATING_BAIDU_PACKAGES = listOf(
        "com.baidu.naviauto.floating",        // 悬浮百度修改版
        "com.baidu.naviauto.overlay",         // 悬浮叠加版
        "com.baidu.naviauto.superv",          // 超级版修改
        "com.baidu.naviauto.xf",              // 悬浮修改版
        "com.baidu.BaiduMap.floating",        // 手机版悬浮修改
        // 如有其他修改版包名，可在此追加
    )

    /**
     * 各地图类型对应的修改版包名列表
     */
    private val FLOATING_MAP_PACKAGES = mapOf(
        MAP_TYPE_AMAP to FLOATING_AMAP_PACKAGES,
        MAP_TYPE_BAIDU to FLOATING_BAIDU_PACKAGES
    )

    /**
     * 各地图类型的显示名称
     */
    private val MAP_DISPLAY_NAMES = mapOf(
        MAP_TYPE_AMAP to "悬浮高德地图",
        MAP_TYPE_BAIDU to "悬浮百度地图"
    )

    /** 默认自动返回延时（毫秒） */
    private const val DEFAULT_RETURN_DELAY = 2500L

    // ======================== 配置读写方法 ========================

    /**
     * 获取当前选中的地图类型
     *
     * @param context 上下文
     * @return "amap" 或 "baidu"，默认 "amap"
     */
    fun getSelectedMapType(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MAP_TYPE, MAP_TYPE_AMAP) ?: MAP_TYPE_AMAP
    }

    /**
     * 设置选中的地图类型
     *
     * @param context 上下文
     * @param mapType "amap" 或 "baidu"
     */
    fun setSelectedMapType(context: Context, mapType: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP_TYPE, mapType)
            .apply()
        Log.d(TAG, "地图类型已切换: $mapType")
    }

    /**
     * 是否启用「启动导航自动返回桌面」
     *
     * @param context 上下文
     * @return true=启用（默认），false=不自动返回
     */
    fun isAutoReturnEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_RETURN, true)
    }

    /**
     * 设置「启动导航自动返回桌面」开关
     *
     * @param context 上下文
     * @param enabled true=启用，false=关闭
     */
    fun setAutoReturnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RETURN, enabled)
            .apply()
        Log.d(TAG, "自动返回桌面: ${if (enabled) "开启" else "关闭"}")
    }

    /**
     * 获取自动返回延时（毫秒）
     */
    fun getReturnDelay(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_RETURN_DELAY, DEFAULT_RETURN_DELAY)
    }

    /**
     * 设置自动返回延时（毫秒）
     */
    fun setReturnDelay(context: Context, delayMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_RETURN_DELAY, delayMs)
            .apply()
    }

    // ======================== 地图应用检测 ========================

    /**
     * 检测指定类型的修改版悬浮地图APP是否已安装
     *
     * @param context 上下文
     * @param mapType 地图类型："amap" 或 "baidu"
     * @return 已安装的包名，若未安装返回 null
     */
    fun findInstalledFloatingMap(context: Context, mapType: String): String? {
        val packages = FLOATING_MAP_PACKAGES[mapType] ?: return null
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                // 检查应用是否已安装
                pm.getPackageInfo(pkg, 0)
                Log.d(TAG, "找到已安装的悬浮地图: $pkg (类型: $mapType)")
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                // 未安装此包名，继续尝试下一个
            }
        }
        return null
    }

    /**
     * 获取当前选中地图类型的已安装包名
     * 若修改版未安装，则返回 null
     *
     * @param context 上下文
     * @return 包名或 null
     */
    fun getInstalledSelectedMapPackage(context: Context): String? {
        val mapType = getSelectedMapType(context)
        return findInstalledFloatingMap(context, mapType)
    }

    /**
     * 获取地图类型的显示名称
     */
    fun getMapDisplayName(mapType: String): String {
        return MAP_DISPLAY_NAMES[mapType] ?: "未知地图"
    }

    /**
     * 检测是否安装了任何修改版悬浮地图（用于全局判断）
     *
     * @param context 上下文
     * @return 已安装的悬浮地图类型列表
     */
    fun getInstalledFloatingMaps(context: Context): List<String> {
        val installed = mutableListOf<String>()
        for (mapType in FLOATING_MAP_PACKAGES.keys) {
            if (findInstalledFloatingMap(context, mapType) != null) {
                installed.add(mapType)
            }
        }
        return installed
    }

    // ======================== 权限校验 ========================

    /**
     * 检测桌面自身是否拥有「显示在其他应用上层」悬浮窗权限
     *
     * Android 6.0+ 需要通过 Settings.canDrawOverlays() 判断。
     * Android 9 (API 28) 完全支持此方法。
     *
     * @param context 上下文
     * @return true=已有权限，false=无权限
     */
    fun hasOwnOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            // Android 6.0 以下默认有权限
            true
        }
    }

    /**
     * 检测第三方APP是否拥有悬浮窗权限
     *
     * 通过 AppOpsManager 查询目标应用是否被授予 SYSTEM_ALERT_WINDOW 操作权限。
     * 这是 Android 公开 API，不需要系统签名。
     *
     * 注意：此方法可能因厂商定制ROM而返回不准确的结果，
     *       Windlink 车机系统基于 Android 9，通常支持此查询。
     *
     * @param context 上下文
     * @param packageName 目标应用包名
     * @return true=已有权限，false=无权限或无法查询
     */
    fun hasAppOverlayPermission(context: Context, packageName: String): Boolean {
        if (packageName.isEmpty()) return false

        return try {
            val pm = context.packageManager
            // 获取目标应用的 ApplicationInfo
            val appInfo = pm.getApplicationInfo(packageName, 0)

            // 方法1：通过 AppOpsManager 检查（Android 4.3+，API 19+）
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

            // OP_SYSTEM_ALERT_WINDOW = 24
            // 使用公开 API 检查模式
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用新方法
                appOpsManager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    appInfo.uid,
                    packageName
                )
            } else {
                // Android 9 及以下使用 checkOpNoThrow
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    appInfo.uid,
                    packageName
                )
            }

            // MODE_ALLOWED = 0 表示已授权
            val hasPermission = mode == AppOpsManager.MODE_ALLOWED
            Log.d(TAG, "应用 $packageName 悬浮窗权限: $hasPermission (mode=$mode)")
            hasPermission
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "应用 $packageName 未安装，无法检查权限")
            false
        } catch (e: Exception) {
            Log.e(TAG, "检查应用悬浮窗权限失败: $packageName", e)
            false
        }
    }

    /**
     * 跳转到桌面自身的悬浮窗权限授权页面
     *
     * @param context 上下文（建议传入 Activity）
     */
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
            Log.e(TAG, "跳转悬浮窗授权页面失败", e)
            // 降级：跳转到应用详情页
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "降级跳转也失败", e2)
                Toast.makeText(context, "无法打开权限设置页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 跳转到第三方地图APP的悬浮窗权限授权页面
     *
     * 注意：Android 不允许直接跳转到其他APP的权限页面，
     *       这里跳转到目标APP的「应用详情页」，用户需手动点击「显示在其他应用上层」。
     *
     * @param context 上下文
     * @param packageName 目标地图APP包名
     */
    fun requestAppOverlayPermission(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(
                context,
                "请在应用详情中开启「显示在其他应用上层」权限",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "跳转第三方应用权限页面失败: $packageName", e)
            // 降级：跳转到应用管理列表
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
     * 2. 检查目标修改版地图APP是否已安装
     * 3. 检查目标地图APP的悬浮窗权限
     * 4. 拉起地图APP
     * 5. 延时后自动切回 PandaDesk 桌面（使地图悬浮窗叠加在桌面上层）
     *
     * @param activity 调用方的 Activity（通常是 MainActivity）
     * @return true=成功启动，false=启动失败（已有错误提示）
     */
    fun launchFloatingNav(activity: Activity): Boolean {
        Log.d(TAG, "===== 开始启动悬浮导航 =====")

        // 步骤1：检查桌面自身悬浮窗权限
        if (!hasOwnOverlayPermission(activity)) {
            Log.w(TAG, "桌面自身缺少悬浮窗权限")
            showPermissionGuide(
                activity,
                "桌面缺少悬浮窗权限",
                "PandaDesk 需要悬浮窗权限才能在导航期间保持桌面在底层显示。\n\n" +
                    "请点击「去授权」，在系统设置中开启「显示在其他应用上层」。",
                targetPackage = null
            )
            return false
        }

        // 步骤2：获取选中的地图类型
        val mapType = getSelectedMapType(activity)
        val mapDisplayName = getMapDisplayName(mapType)
        Log.d(TAG, "选中地图类型: $mapType ($mapDisplayName)")

        // 步骤3：检查修改版地图APP是否已安装
        val mapPackage = findInstalledFloatingMap(activity, mapType)
        if (mapPackage == null) {
            Log.w(TAG, "修改版悬浮地图未安装: $mapType")
            showMapNotInstalledGuide(activity, mapType)
            return false
        }

        // 步骤4：检查目标地图APP的悬浮窗权限
        if (!hasAppOverlayPermission(activity, mapPackage)) {
            Log.w(TAG, "地图APP缺少悬浮窗权限: $mapPackage")
            showPermissionGuide(
                activity,
                "$mapDisplayName 缺少悬浮窗权限",
                "$mapDisplayName 需要悬浮窗权限才能在桌面上层叠加显示。\n\n" +
                    "请点击「去授权」，在应用详情中开启「显示在其他应用上层」。",
                targetPackage = mapPackage
            )
            return false
        }

        // 步骤5：拉起地图APP
        Log.d(TAG, "拉起地图APP: $mapPackage")
        val launched = launchMapApp(activity, mapPackage)
        if (!launched) {
            Toast.makeText(activity, "启动 $mapDisplayName 失败", Toast.LENGTH_SHORT).show()
            return false
        }

        Toast.makeText(activity, "正在启动 $mapDisplayName ...", Toast.LENGTH_SHORT).show()

        // 步骤6：延时后自动切回 PandaDesk 桌面
        if (isAutoReturnEnabled(activity)) {
            val delay = getReturnDelay(activity)
            Log.d(TAG, "将在 ${delay}ms 后自动返回桌面")
            Handler(Looper.getMainLooper()).postDelayed({
                returnToDesktop(activity)
            }, delay)
        }

        Log.d(TAG, "===== 悬浮导航启动完成 =====")
        return true
    }

    /**
     * 拉起地图APP
     *
     * 使用 PackageManager.getLaunchIntentForPackage() 获取启动Intent，
     * 这是 Android 公开 API，不需要特殊权限。
     *
     * @param context 上下文
     * @param packageName 地图APP包名
     * @return true=成功拉起，false=失败
     */
    private fun launchMapApp(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 传递悬浮模式参数（部分修改版地图支持识别）
                intent.putExtra("floating_mode", true)
                intent.putExtra("overlay_mode", true)
                context.startActivity(intent)
                Log.d(TAG, "地图APP已拉起: $packageName")
                true
            } else {
                Log.e(TAG, "无法获取地图APP的启动Intent: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉起地图APP异常: $packageName", e)
            false
        }
    }

    /**
     * 自动返回桌面
     *
     * 通过 Intent 启动 PandaDesk 自身的 MainActivity，
     * 使桌面回到前台，地图悬浮窗叠加在桌面上层。
     *
     * 使用 FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_REORDER_TO_FRONT 确保桌面被拉到前台。
     *
     * @param context 上下文
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
                // 标记为从导航返回，MainActivity 可据此做特殊处理
                putExtra("from_floating_nav", true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "返回桌面失败", e)
            // 降级：使用 Home Intent
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

    // ======================== 错误引导对话框 ========================

    /**
     * 显示权限引导对话框
     *
     * @param context 上下文
     * @param title 标题
     * @param message 提示信息
     * @param targetPackage 目标包名（null=桌面自身，非null=第三方地图APP）
     */
    private fun showPermissionGuide(
        context: Context,
        title: String,
        message: String,
        targetPackage: String?
    ) {
        if (context !is Activity) return

        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("去授权") { _, _ ->
                if (targetPackage != null) {
                    requestAppOverlayPermission(context, targetPackage)
                } else {
                    requestOwnOverlayPermission(context)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示地图未安装引导对话框
     *
     * 告知用户需要安装修改版悬浮地图，原版不支持悬浮导航。
     *
     * @param context 上下文
     * @param mapType 地图类型
     */
    private fun showMapNotInstalledGuide(context: Context, mapType: String) {
        if (context !is Activity) return

        val mapName = getMapDisplayName(mapType)
        android.app.AlertDialog.Builder(context)
            .setTitle("未安装 $mapName")
            .setMessage(
                "检测到当前未安装 $mapName（修改版）。\n\n" +
                    "【重要限制】\n" +
                    "官方原版高德/百度地图不支持悬浮导航功能。\n" +
                    "必须安装民间修改版悬浮地图车机版才能实现悬浮叠加。\n\n" +
                    "请在车机应用市场或社区论坛搜索 $mapName 修改版安装。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 检查并显示完整的权限状态（用于设置页展示）
     *
     * @param context 上下文
     * @return 权限状态信息对象
     */
    fun checkPermissionStatus(context: Context): PermissionStatus {
        val mapType = getSelectedMapType(context)
        val ownPermission = hasOwnOverlayPermission(context)
        val mapPackage = findInstalledFloatingMap(context, mapType)
        val mapInstalled = mapPackage != null
        val mapPermission = if (mapPackage != null) {
            hasAppOverlayPermission(context, mapPackage)
        } else {
            false
        }

        return PermissionStatus(
            mapType = mapType,
            mapDisplayName = getMapDisplayName(mapType),
            ownOverlayPermission = ownPermission,
            mapInstalled = mapInstalled,
            mapPackage = mapPackage,
            mapOverlayPermission = mapPermission,
            autoReturnEnabled = isAutoReturnEnabled(context),
            ready = ownPermission && mapInstalled && mapPermission
        )
    }

    /**
     * 权限状态数据类
     */
    data class PermissionStatus(
        val mapType: String,            // 当前地图类型
        val mapDisplayName: String,     // 地图显示名称
        val ownOverlayPermission: Boolean,  // 桌面自身悬浮窗权限
        val mapInstalled: Boolean,      // 修改版地图是否已安装
        val mapPackage: String?,        // 已安装的地图包名
        val mapOverlayPermission: Boolean,  // 地图APP悬浮窗权限
        val autoReturnEnabled: Boolean, // 自动返回桌面开关
        val ready: Boolean              // 是否全部就绪可启动
    )
}
