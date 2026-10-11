package app.snapfade

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
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

        findViewById<View>(R.id.enable).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
            Shots.setEnabled(this, true)
            ShotService.start(this)
            handler.postDelayed(::refresh, 300)
        }
        findViewById<View>(R.id.disable).setOnClickListener {
            Shots.setEnabled(this, false)
            ShotService.stop(this)
            handler.postDelayed(::refresh, 300)
        }
        findViewById<View>(R.id.overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.files).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<ListView>(R.id.list).setOnItemClickListener { parent, _, pos, _ ->
            Shots.copyToClipboard(this, parent.getItemAtPosition(pos) as File)
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        setupExpiry()
    }

    private fun setupExpiry() {
        val group = findViewById<RadioGroup>(R.id.expiry)
        Shots.EXPIRY_OPTIONS.forEachIndexed { i, h -> (group.getChildAt(i) as RadioButton).text = "${h}h" }
        val idx = Shots.EXPIRY_OPTIONS.indexOf(Shots.getTtlHours(this))
        if (idx >= 0) (group.getChildAt(idx) as RadioButton).isChecked = true
        group.setOnCheckedChangeListener { g, id ->
            Shots.setTtlHours(this, Shots.EXPIRY_OPTIONS[g.indexOfChild(g.findViewById(id))])
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
        val running = ShotService.running
        val overlay = Settings.canDrawOverlays(this)
        val files = Environment.isExternalStorageManager()
        val ready = running && files

        findViewById<TextView>(R.id.status).setText(if (ready) R.string.status_ready else R.string.status_setup)
        findViewById<TextView>(R.id.hint).setText(
            when {
                !ready -> R.string.hint_setup
                !overlay -> R.string.hint_no_overlay
                else -> R.string.hint_ready
            }
        )
        findViewById<View>(R.id.enable).visibility = if (running) View.GONE else View.VISIBLE
        findViewById<View>(R.id.disable).visibility = if (running) View.VISIBLE else View.GONE
        findViewById<View>(R.id.overlay).visibility = if (overlay) View.GONE else View.VISIBLE
        findViewById<View>(R.id.files).visibility = if (files) View.GONE else View.VISIBLE

        Shots.sweep(this)
        val shots = Shots.all(this)
        findViewById<View>(R.id.empty).visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
        findViewById<ListView>(R.id.list).adapter = ShotAdapter(this, shots)
    }

    private class ShotAdapter(ctx: Context, shots: List<File>) : ArrayAdapter<File>(ctx, 0, shots) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: View.inflate(context, R.layout.row_shot, null)
            val f = getItem(position)!!
            row.findViewById<ImageView>(R.id.thumb).setImageBitmap(
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
            )
            row.findViewById<TextView>(R.id.left).text = Shots.timeLeft(context, f)
            row.findViewById<View>(R.id.save).setOnClickListener {
                Shots.saveToGallery(context, f)
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            }
            row.findViewById<View>(R.id.share).setOnClickListener { Shots.share(context, f) }
            row.findViewById<View>(R.id.delete).setOnClickListener { Shots.remove(context, f) }
            return row
        }
    }
}
