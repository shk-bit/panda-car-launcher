package com.pandora.carlauncher

import android.app.Application
import me.jessyan.autosize.AutoSizeConfig

class PandaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.instance.init(this)
        
        // 初始化 AndroidAutoSize
        AutoSizeConfig.getInstance()
            .setUseDeviceSize(false)
            .setExcludeFontScale(true)
    }
}
