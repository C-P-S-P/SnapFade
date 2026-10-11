package app.tempss

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 60_000) }
    }
    private val watcher by lazy {
        object : FileObserver(Shots.dir(this), CLOSE_WRITE or DELETE) {
            override fun onEvent(event: Int, path: String?) { handler.post(::refresh) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.files).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<ListView>(R.id.list).setOnItemClickListener { parent, _, pos, _ ->
            Shots.copyToClipboard(this, parent.getItemAtPosition(pos) as File)
            Toast.makeText(this, R.string.copied_again, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        watcher.startWatching()
        handler.post(tick)
    }

    override fun onPause() {
        watcher.stopWatching()
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val a11y = serviceEnabled()
        val files = Environment.isExternalStorageManager()
        val ready = a11y && files
        findViewById<TextView>(R.id.status).setText(
            when {
                ready -> R.string.status_on
                !a11y -> R.string.status_no_a11y
                else -> R.string.status_no_files
            }
        )
        findViewById<View>(R.id.settings).visibility = if (a11y) View.GONE else View.VISIBLE
        findViewById<View>(R.id.files).visibility = if (files) View.GONE else View.VISIBLE

        Shots.sweep(this)
        val shots = Shots.all(this)
        findViewById<View>(R.id.empty).visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.list).adapter = ShotAdapter(this, shots)
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, ShotService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.flattenToString() == me }
    }

    private class ShotAdapter(ctx: Context, shots: List<File>) : ArrayAdapter<File>(ctx, 0, shots) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: View.inflate(context, R.layout.row_shot, null)
            val f = getItem(position)!!
            row.findViewById<ImageView>(R.id.thumb).setImageBitmap(
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            )
            row.findViewById<TextView>(R.id.name).text = f.name
            row.findViewById<TextView>(R.id.left).text = Shots.timeLeft(context, f)
            row.findViewById<View>(R.id.save).setOnClickListener {
                Shots.saveToGallery(context, f)
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            row.findViewById<View>(R.id.delete).setOnClickListener {
                Shots.remove(context, f) // FileObserver in the activity refreshes the list
            }
            return row
        }
    }
}
