package io.ck.lx04voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val cfg = Config(ctx)
            if (cfg.isReady) {
                ctx.startService(Intent(ctx, ListenerService::class.java).apply {
                    action = ListenerService.ACTION_START
                })
            }
        }
    }
}
