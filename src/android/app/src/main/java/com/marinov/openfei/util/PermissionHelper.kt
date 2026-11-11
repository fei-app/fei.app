package com.marinov.openfei.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

object PermissionHelper {

    private const val TAG = "PermissionHelper"
    const val REQUEST_PERMISSIONS_CODE = 100
    const val PREFS_NAME = "app_prefs"
    private const val PREF_KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"

    fun solicitarPermissoesIniciais(activity: Activity) {
        // ★ BUG 24: não tenta mais ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // (permissão restrita no Google Play e raramente necessária).
        // Mantemos o app funcional sem ela: o WorkManager usa constraints
        // CONNECTED/UNMETERED e o AlarmManager usa setAndAllowWhileIdle.
        solicitarPermissoesNecessarias(activity)
    }

    private fun solicitarPermissoesNecessarias(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jaSolicitadas = prefs.getBoolean(PREF_KEY_NOTIFICATIONS_REQUESTED, false)
        if (jaSolicitadas) return

        val permissoesParaSolicitar = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissoesParaSolicitar.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissoesParaSolicitar.isNotEmpty()) {
            Log.d(TAG, "Solicitando permissões: $permissoesParaSolicitar")
            ActivityCompat.requestPermissions(
                activity,
                permissoesParaSolicitar.toTypedArray(),
                REQUEST_PERMISSIONS_CODE
            )
        }
    }

    /**
     * ★ BUG 23: só marca como "já pedido" se o usuário respondeu.
     * Chamado a partir do onRequestPermissionsResult da Activity.
     */
    fun onPermissionResult(activity: Activity, requestCode: Int) {
        if (requestCode != REQUEST_PERMISSIONS_CODE) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(PREF_KEY_NOTIFICATIONS_REQUESTED, true) }
    }

}