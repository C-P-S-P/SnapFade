package app.snapfade

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Invisible trampoline for deleting a gallery file on Android 12+. MediaProvider's
 * createDeleteRequest must be launched for a result from an Activity (it reads the calling
 * package); a Service has none. With MANAGE_MEDIA granted no dialog is shown, so this is a
 * translucent flash that finishes as soon as the request returns.
 */
class DeleteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
        startIntentSenderForResult(request.intentSender, 0, null, 0, 0, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = finish()

    companion object {
        fun start(ctx: Context, uri: Uri) = ctx.startActivity(
            Intent(ctx, DeleteActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
