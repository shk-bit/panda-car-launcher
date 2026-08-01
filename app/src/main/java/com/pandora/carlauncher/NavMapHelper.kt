package com.pandora.carlauncher

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * 嵌入式地图助手
 *
 * 【功能】
 * 在桌面导航区域（nav_activity_container）内显示一个可交互的地图：
 * - 使用高德地图瓦片源（无需 API Key）
 * - 支持双指缩放、拖动、旋转
 * - 显示当前位置标记
 * - 显示指北针和比例尺
 *
 * 【原理】
 * 基于 osmdroid 开源地图库 + 高德瓦片服务器
 * 不集成任何地图 SDK，不需要 API Key
 *
 * 【交互流程】
 * 1. 用户点击导航区域 → 显示嵌入地图
 * 2. 用户可缩放、拖动、旋转查看地图
 * 3. 用户点击「开始导航」→ 启动选中的地图 APP 进行全屏导航
 * 4. 返回桌面后地图区域恢复显示嵌入地图
 */
class NavMapHelper(private val context: Context) {

    companion object {
        private const val TAG = "NavMapHelper"
        private const val DEFAULT_ZOOM = 16.0
        private val DEFAULT_CENTER = GeoPoint(39.9087, 116.3975)
    }

    private var mapView: MapView? = null
    private var locationOverlay: MyLocationNewOverlay? = null
    private var compassOverlay: CompassOverlay? = null
    private var scaleBarOverlay: ScaleBarOverlay? = null
    private var rotationOverlay: RotationGestureOverlay? = null

    var isInitialized = false
        private set

    /**
     * 初始化 osmdroid 配置
     */
    private fun initOsmdroidConfig() {
        try {
            val config = Configuration.getInstance()
            config.userAgentValue = context.packageName
            val cacheDir = context.filesDir
            config.osmdroidTileCache = java.io.File(cacheDir, "osm_tiles")
            config.osmdroidBasePath = java.io.File(cacheDir, "osm")
            config.cacheMapTileCount = 200
            config.cacheMapTileMaxBytes = 50L * 1024 * 1024
            config.expireCacheAfter = 30
            Log.d(TAG, "osmdroid 配置完成")
        } catch (e: Exception) {
            Log.e(TAG, "osmdroid 配置失败", e)
        }
    }

    /**
     * 高德地图瓦片源（标准地图）
     *
     * 瓦片URL格式：https://webrd0{n}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}
     */
    private fun createAMapTileSource(): OnlineTileSourceBase {
        return object : OnlineTileSourceBase(
            "AMapStandard",
            0, 19, 256, ".png",
            arrayOf(
                "https://webrd01.is.autonavi.com/appmaptile",
                "https://webrd02.is.autonavi.com/appmaptile",
                "https://webrd03.is.autonavi.com/appmaptile",
                "https://webrd04.is.autonavi.com/appmaptile"
            ),
            "高德地图"
        ) {
            override fun getTileURLString(pTile: Long): String {
                val zoom = MapTileIndex.getZoom(pTile)
                val x = MapTileIndex.getX(pTile)
                val y = MapTileIndex.getY(pTile)
                return baseUrl + "?lang=zh_cn&size=1&scale=1&style=8&x=$x&y=$y&z=$zoom"
            }
        }
    }

    /**
     * 在指定容器中创建并显示地图
     *
     * @param container 导航区域容器（nav_activity_container）
     * @return true=创建成功
     */
    fun createMap(container: FrameLayout): Boolean {
        if (mapView != null) {
            destroyMap()
        }

        try {
            initOsmdroidConfig()

            mapView = MapView(context).apply {
                setTileSource(createAMapTileSource())
                setMultiTouchControls(true)
                setUseDataConnection(true)
                minZoomLevel = 3.0
                maxZoomLevel = 19.0
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(DEFAULT_CENTER)
                setMapBackgroundColor(Color.parseColor("#1a1f2e"))
            }

            // 旋转手势
            rotationOverlay = RotationGestureOverlay(mapView).apply {
                isEnabled = true
            }
            mapView?.overlays?.add(rotationOverlay)

            // 比例尺
            scaleBarOverlay = ScaleBarOverlay(mapView).apply {
                setCentred(true)
                setScaleBarOffset(20, 20)
            }
            mapView?.overlays?.add(scaleBarOverlay)

            // 指北针
            compassOverlay = CompassOverlay(context, mapView).apply {
                setCompassCenter(36f, 36f)
            }
            mapView?.overlays?.add(compassOverlay)

            // 我的位置
            try {
                val locationProvider = GpsMyLocationProvider(context)
                locationOverlay = MyLocationNewOverlay(locationProvider, mapView).apply {
                    enableMyLocation()
                    enableFollowLocation()
                }
                mapView?.overlays?.add(locationOverlay)
            } catch (e: Exception) {
                Log.w(TAG, "位置定位初始化失败（可能缺少定位权限）", e)
            }

            // 添加到容器
            container.removeAllViews()
            container.addView(mapView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            isInitialized = true
            Log.d(TAG, "嵌入式地图创建成功")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "创建地图失败", e)
            isInitialized = false
            return false
        }
    }

    /**
     * 移动到指定位置
     */
    fun moveToLocation(lat: Double, lon: Double, zoom: Double = DEFAULT_ZOOM) {
        mapView?.let {
            it.controller.animateTo(GeoPoint(lat, lon), zoom, 1000L)
        }
    }

    /**
     * 销毁地图，释放资源
     */
    fun destroyMap() {
        try {
            locationOverlay?.disableMyLocation()
            locationOverlay = null
            compassOverlay?.disableCompass()
            compassOverlay = null
            scaleBarOverlay = null
            rotationOverlay = null
            mapView?.onDetach()
            mapView = null
            isInitialized = false
            Log.d(TAG, "地图已销毁")
        } catch (e: Exception) {
            Log.e(TAG, "销毁地图失败", e)
        }
    }

    /**
     * 暂停地图（Activity onPause 时调用）
     */
    fun onPause() {
        mapView?.onPause()
    }

    /**
     * 恢复地图（Activity onResume 时调用）
     */
    fun onResume() {
        mapView?.onResume()
    }

    /**
     * 重新启用位置追踪（定位权限授权后调用）
     */
    fun reEnableLocation() {
        try {
            if (mapView != null && locationOverlay == null) {
                val locationProvider = GpsMyLocationProvider(context)
                locationOverlay = MyLocationNewOverlay(locationProvider, mapView).apply {
                    enableMyLocation()
                    enableFollowLocation()
                }
                mapView?.overlays?.add(locationOverlay)
                mapView?.invalidate()
                Log.d(TAG, "位置追踪已重新启用")
            }
        } catch (e: Exception) {
            Log.w(TAG, "重新启用位置追踪失败", e)
        }
    }
}
