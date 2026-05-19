package com.pandora.carlauncher

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pandora.carlauncher.PipActivity

class AppManagerActivity : AppCompatActivity() {

    private lateinit var rvAppList: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var adapter: AppAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var allApps = mutableListOf<AppRecognizer.AppInfo>()
    private var currentCategory: AppRecognizer.AppCategory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_manager)

        rvAppList = findViewById(R.id.rv_app_list)
        etSearch = findViewById(R.id.et_search)

        val spanCount = getSpanCount()
        gridLayoutManager = GridLayoutManager(this, spanCount)
        rvAppList.layoutManager = gridLayoutManager
        adapter = AppAdapter()
        rvAppList.adapter = adapter

        loadApps()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<ImageView>(R.id.btn_back)?.setOnClickListener {
            finish()
        }

        findViewById<ImageView>(R.id.btn_refresh)?.setOnClickListener {
            refreshApps()
        }

        setupCategoryTabs()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        gridLayoutManager.spanCount = getSpanCount()
    }

    private fun getSpanCount(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            5
        } else {
            4
        }
    }

    private fun setupCategoryTabs() {
        val tabs = mapOf(
            R.id.tab_all to null,
            R.id.tab_navigation to AppRecognizer.AppCategory.NAVIGATION,
            R.id.tab_music to AppRecognizer.AppCategory.MUSIC,
            R.id.tab_video to AppRecognizer.AppCategory.VIDEO,
            R.id.tab_tool to AppRecognizer.AppCategory.TOOL
        )

        for ((tabId, category) in tabs) {
            findViewById<TextView>(tabId)?.setOnClickListener {
                currentCategory = category
                for ((id, _) in tabs) {
                    val tab = findViewById<TextView>(id)
                    tab?.setTextColor(if (id == tabId) getColor(R.color.primary) else getColor(R.color.text_secondary))
                    tab?.setBackgroundResource(if (id == tabId) R.drawable.bg_icon_cyan else R.drawable.bg_card)
                }
                filterApps(etSearch.text.toString())
            }
        }
    }

    private fun loadApps() {
        allApps.clear()
        allApps.addAll(AppRecognizer.getAllInstalledApps(this))
        adapter.notifyDataSetChanged()
    }

    private fun refreshApps() {
        // 清空搜索框
        etSearch.setText("")
        // 重置分类为全部
        currentCategory = null
        findViewById<TextView>(R.id.tab_all)?.performClick()
        // 重新加载应用列表
        loadApps()
        Toast.makeText(this, "应用列表已刷新", Toast.LENGTH_SHORT).show()
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) {
            if (currentCategory != null) {
                allApps.filter { it.category == currentCategory }
            } else {
                allApps
            }
        } else {
            val searchResult = AppRecognizer.searchApps(this, query)
            if (currentCategory != null) {
                searchResult.filter { it.category == currentCategory }
            } else {
                searchResult
            }
        }
        adapter.updateData(filtered)
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var apps = mutableListOf<AppRecognizer.AppInfo>()

        fun updateData(newApps: List<AppRecognizer.AppInfo>) {
            apps.clear()
            apps.addAll(newApps)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_grid_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.bind(app)
        }

        override fun getItemCount(): Int = apps.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
            private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)

            fun bind(app: AppRecognizer.AppInfo) {
                tvName.text = app.appName
                
                if (app.icon != null) {
                    ivIcon.setImageDrawable(app.icon)
                } else {
                    ivIcon.setImageResource(R.drawable.ic_apps)
                }

                // 点击显示详细信息弹窗
                itemView.setOnClickListener {
                    showAppInfoDialog(app)
                }
            }
        }
    }

    /**
     * 显示应用详细信息弹窗
     */
    private fun showAppInfoDialog(app: AppRecognizer.AppInfo) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_app_info)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 设置应用图标
        dialog.findViewById<ImageView>(R.id.iv_app_icon)?.let {
            if (app.icon != null) {
                it.setImageDrawable(app.icon)
            } else {
                it.setImageResource(R.drawable.ic_apps)
            }
        }

        // 设置应用名称
        dialog.findViewById<TextView>(R.id.tv_app_name)?.text = app.appName

        // 设置包名 - 点击复制
        val tvPackageName = dialog.findViewById<TextView>(R.id.tv_package_name)
        tvPackageName?.text = "包名: ${app.packageName}"
        tvPackageName?.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("包名", app.packageName)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "已复制包名: ${app.packageName}", Toast.LENGTH_SHORT).show()
        }

        // 获取应用详细信息
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(app.packageName, 0)
            }

            // 版本信息
            val versionName = packageInfo.versionName ?: "未知"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            dialog.findViewById<TextView>(R.id.tv_version)?.text = "版本: $versionName ($versionCode)"

            // 安装时间
            val firstInstallTime = packageInfo.firstInstallTime
            val installDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(firstInstallTime))
            dialog.findViewById<TextView>(R.id.tv_install_time)?.text = "安装时间: $installDate"

            // 更新时间和包大小
            val lastUpdateTime = packageInfo.lastUpdateTime
            val updateDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastUpdateTime))
            dialog.findViewById<TextView>(R.id.tv_update_time)?.text = "更新时间: $updateDate"

        } catch (e: Exception) {
            dialog.findViewById<TextView>(R.id.tv_version)?.text = "版本: 获取失败"
            dialog.findViewById<TextView>(R.id.tv_install_time)?.text = "安装时间: 获取失败"
            dialog.findViewById<TextView>(R.id.tv_update_time)?.text = "更新时间: 获取失败"
        }

        // 启动按钮
        dialog.findViewById<LinearLayout>(R.id.btn_launch)?.setOnClickListener {
            dialog.dismiss()
            openApp(app.packageName)
        }

        // 卸载按钮
        dialog.findViewById<LinearLayout>(R.id.btn_uninstall)?.setOnClickListener {
            dialog.dismiss()
            uninstallApp(app)
        }

        // 应用详情按钮
        dialog.findViewById<LinearLayout>(R.id.btn_app_info)?.setOnClickListener {
            dialog.dismiss()
            showAppDetails(app)
        }

        // 添加到桌面按钮
        dialog.findViewById<LinearLayout>(R.id.btn_add_desktop)?.setOnClickListener {
            dialog.dismiss()
            addToDesktop(app)
        }

        // 悬浮窗按钮
        dialog.findViewById<LinearLayout>(R.id.btn_pip)?.setOnClickListener {
            dialog.dismiss()
            startPipMode(app)
        }

        // 关闭按钮
        dialog.findViewById<ImageView>(R.id.btn_close)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 显示应用详情页面（系统设置页面）
     */
    private fun showAppDetails(app: AppRecognizer.AppInfo) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${app.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开应用详情", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPipMode(app: AppRecognizer.AppInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PipActivity.start(this, app.packageName, app.appName)
        } else {
            Toast.makeText(this, "画中画功能需要 Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToDesktop(app: AppRecognizer.AppInfo) {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val customAppsJson = prefs.getString(MainActivity.KEY_CUSTOM_APPS, "[]") ?: "[]"
        
        if (customAppsJson.contains("\"packageName\":\"${app.packageName}\"")) {
            Toast.makeText(this, "该应用已在桌面", Toast.LENGTH_SHORT).show()
            return
        }
        
        val count = org.json.JSONArray(customAppsJson).length()
        if (count >= MainActivity.MAX_CUSTOM_APPS) {
            Toast.makeText(this, R.string.custom_app_max_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val jsonArray = org.json.JSONArray(customAppsJson)
        val obj = org.json.JSONObject()
        obj.put("packageName", app.packageName)
        obj.put("appName", app.appName)
        jsonArray.put(obj)
        
        prefs.edit().putString(MainActivity.KEY_CUSTOM_APPS, jsonArray.toString()).apply()
        Toast.makeText(this, "已添加: ${app.appName}", Toast.LENGTH_SHORT).show()
    }

    private fun uninstallApp(app: AppRecognizer.AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("卸载应用")
            .setMessage("确定要卸载 ${app.appName} 吗？")
            .setPositiveButton("确定") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法卸载此应用", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
