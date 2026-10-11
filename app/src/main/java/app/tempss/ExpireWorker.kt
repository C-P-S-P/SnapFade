package app.tempss

import android.content.ClipboardManager
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/** Runs 6 h after a capture: deletes that shot so the clipboard URI goes dead. */
class ExpireWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        inputData.getString(Shots.KEY_PATH)?.let { File(it).delete() }
        Shots.sweep(applicationContext)

        // ponytail: Android 10+ hides clipboard contents from background apps, so this read
        // usually returns null and the clip stays listed but un-pasteable (file is gone).
        val cm = applicationContext.getSystemService(ClipboardManager::class.java)
        if (cm.primaryClipDescription?.label == Shots.CLIP_LABEL) cm.clearPrimaryClip()
        return Result.success()
    }
}
