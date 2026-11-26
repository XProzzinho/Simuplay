package com.geanalves.simulplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Abre o Simulplay quando a TV liga (se o celular deixou ligado essa opção). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!ctx.getSharedPreferences("simulplay", Context.MODE_PRIVATE).getBoolean("boot_start", true)) return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
