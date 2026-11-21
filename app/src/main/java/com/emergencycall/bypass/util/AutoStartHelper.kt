package com.emergencycall.bypass.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /**
     * Retorna o nome da marca do dispositivo formatado.
     */
    val deviceBrand: String
        get() = Build.MANUFACTURER.lowercase().replaceFirstChar { it.uppercase() }

    /**
     * Tenta abrir a tela de Início Automático (Autostart) ou Gerenciamento de Bateria do fabricante.
     * Se falhar ou não encontrar a tela específica do fabricante, abre os Detalhes do Aplicativo no Android.
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        when {
            // Xiaomi / Redmi / POCO (MIUI / HyperOS)
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                intents.add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")))
            }

            // Samsung (One UI)
            manufacturer.contains("samsung") -> {
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")))
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.app.dashboard.SmartManagerDashBoardActivity")))
                intents.add(Intent().setComponent(ComponentName("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")))
            }

            // Huawei / Honor (EMUI / HarmonyOS)
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
            }

            // Oppo / Realme / OnePlus (ColorOS / OxygenOS)
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
            }

            // Vivo / iQOO (FuntouchOS / OriginOS)
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
            }

            // Asus
            manufacturer.contains("asus") -> {
                intents.add(Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")))
                intents.add(Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")))
            }
        }

        // Tenta cada uma das intents específicas da marca
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Sucesso ao abrir intent do fabricante: ${intent.component}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Intent do fabricante não disponível: ${intent.component}")
            }
        }

        // Fallback: Abre a tela de Configurações do Aplicativo no Sistema
        return try {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir configurações do app", e)
            false
        }
    }
}
