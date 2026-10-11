package app.snapfade

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Shots.isEnabled(ctx)) ShotService.start(ctx)
    }
}
