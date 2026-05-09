package com.marinov.openfei

import android.app.Application
import android.os.Build
import com.google.android.gms.ads.MobileAds
import com.google.android.material.color.DynamicColors

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        // Inicializa o SDK do AdMob de forma assíncrona, sem bloquear a UI
        MobileAds.initialize(this) {}
    }
}