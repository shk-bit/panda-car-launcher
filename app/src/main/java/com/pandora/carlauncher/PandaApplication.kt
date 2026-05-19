package com.pandora.carlauncher

import android.app.Application
import androidx.multidex.MultiDexApplication
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits

class PandaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.instance.init(this)
        
        // 初始化 AndroidAutoSize
        AutoSizeConfig.getInstance()
            .setUseDeviceSize(false)
            .setExcludeFontScale(true)
            .setPrivateMode(false)
    }
}
