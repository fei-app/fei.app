package com.marinov.openfei

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.judemanutd.autostarter.AutoStartPermissionHelper

object PermissionHelper {

    private const val TAG = "PermissionHelper"
    private const val REQUEST_PERMISSIONS_CODE = 100
    const val PREFS_NAME = "app_prefs"
    private const val PREF_KEY_PERMISSIONS_REQUESTED = "permissions_requested"
    private const val PREF_KEY_BATTERY_REQUESTED = "battery_request_done"

    fun solicitarPermissoesIniciais(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jaSolicitadas = prefs.getBoolean(PREF_KEY_PERMISSIONS_REQUESTED, false)
        if (jaSolicitadas) return

        solicitarPermissoesNecessarias(activity)
        solicitarIsencaoOtimizacaoBateria(activity)

        // Correção: Solicitar whitelist em OEMs específicos (Xiaomi, Samsung, etc)
        // para garantir o funcionamento em background
        try {
            if (AutoStartPermissionHelper.getInstance().isAutoStartPermissionAvailable(activity)) {
                AutoStartPermissionHelper.getInstance().getAutoStartPermission(activity)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao solicitar permissão de AutoStart (OEM)", e)
        }

        prefs.edit { putBoolean(PREF_KEY_PERMISSIONS_REQUESTED, true) }
    }

    private fun solicitarPermissoesNecessarias(activity: Activity) {
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

    @SuppressLint("BatteryLife")
    private fun solicitarIsencaoOtimizacaoBateria(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val batteryRequested = prefs.getBoolean(PREF_KEY_BATTERY_REQUESTED, false)
        if (batteryRequested) return

        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                prefs.edit { putBoolean(PREF_KEY_BATTERY_REQUESTED, true) }
                return
            }

            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${activity.packageName}".toUri()
                }
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS não disponível", e)
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {
                    Log.w(TAG, "Não foi possível abrir configurações de bateria", ex)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException ao requisitar isenção", e)
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {
                    Log.w(TAG, "Não foi possível abrir configurações de bateria", ex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao abrir solicitação de isenção de bateria", e)
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {
                    Log.w(TAG, "Não foi possível abrir configurações de bateria", ex)
                }
            } finally {
                prefs.edit { putBoolean(PREF_KEY_BATTERY_REQUESTED, true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar isenção de otimização de bateria", e)
        }
    }
}