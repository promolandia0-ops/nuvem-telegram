package br.geracaoai.nuvem.work

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import br.geracaoai.nuvem.data.Prefs2

object BatteryGuide {
    fun ignoring(c: Context): Boolean = try {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(c.packageName)
    } catch (_: Exception) { false }

    fun shouldShowCard(c: Context): Boolean = !Prefs2.batteryGuideDismissed && !ignoring(c)

    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(pkg: String): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$pkg"))

    fun settingsIntents(pkg: String): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent("com.samsung.android.sm.ACTION_BATTERY"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
    )

    fun openBest(c: Context) {
        val pkg = c.packageName
        try { c.startActivity(requestIgnoreIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        for (i in settingsIntents(pkg)) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }
}
