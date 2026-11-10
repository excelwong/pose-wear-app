package com.example.posewear

import android.app.Application
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        // 关键：启动时自动装载本机保存的设置（IP/端口、素材库目录、生成参数等）
        Store.cfg = Settings.load(this)
        Store.personLocator = Store.cfg.personPath
        Store.lastResult = Store.cfg.lastResult
        Store.keepBackground = Store.cfg.keepBackground
        Store.ratio = Store.cfg.ratio
        // 三个素材页选项也随设置自动装载，避免重启后按钮选中态丢失
        Store.keepCostume = Store.cfg.keepCostume
        Store.nude = Store.cfg.nude
        Store.keepPose = Store.cfg.keepPose
        Store.normalizeModes()
    }

    companion object {
        lateinit var ctx: Context
            private set
    }
}
