package app.tempss

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore.Images.Media
import android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/** The cached screenshots: one file each, expiry = file mtime + 6 h. */
object Shots {
    const val CLIP_LABEL = "tempSS"
    const val KEY_PATH = "path"
    val TTL_MS: Long = TimeUnit.HOURS.toMillis(6)

    fun dir(ctx: Context): File = File(ctx.cacheDir, "shots").apply { mkdirs() }

    fun all(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun msLeft(f: File): Long = f.lastModified() + TTL_MS - System.currentTimeMillis()

    /** Delete anything past its 6 h. Called on open, on capture and from the worker. */
    fun sweep(ctx: Context) = all(ctx).filter { msLeft(it) <= 0 }.forEach { it.delete() }

    fun timeLeft(ctx: Context, f: File): String {
        val m = (msLeft(f) / 60_000).coerceAtLeast(0)
        return ctx.getString(R.string.time_left, m / 60, m % 60)
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** Delete now. Also clears the clipboard if this shot is what's on it (readable: we're foreground). */
    fun remove(ctx: Context, f: File) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        if (cm.primaryClip?.getItemAt(0)?.uri == uri(ctx, f)) cm.clearPrimaryClip()
        f.delete()
        WorkManager.getInstance(ctx).cancelUniqueWork(f.name)
    }

    fun copyToClipboard(ctx: Context, f: File) {
        val uri = uri(ctx, f)
        for (p in listOf("com.android.systemui", "android")) { // let the system clipboard preview read it
            runCatching { ctx.grantUriPermission(p, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(ctx.contentResolver, CLIP_LABEL, uri))
    }

    fun scheduleExpiry(ctx: Context, f: File) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            f.name, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ExpireWorker>()
                .setInitialDelay(TTL_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_PATH to f.path))
                .build()
        )
    }

    /** Insert into Pictures/Screenshots. No storage permission needed for our own inserts. */
    fun saveToGallery(ctx: Context, f: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "image/png"
        val values = ContentValues().apply {
            put(Media.DISPLAY_NAME, f.name)
            put(Media.MIME_TYPE, mime)
            put(Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
            put(Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(Media.getContentUri(VOLUME_EXTERNAL_PRIMARY), values) ?: return
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
