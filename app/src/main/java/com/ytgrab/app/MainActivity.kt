package com.ytgrab.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val app: App get() = application as App

    private lateinit var urlBox: EditText
    private lateinit var btnPaste: ImageView
    private lateinit var btnBell: View
    private lateinit var badge: View
    private lateinit var statusTv: TextView
    private lateinit var infoArea: View
    private lateinit var thumb: ImageView
    private lateinit var titleTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var videoList: LinearLayout
    private lateinit var audioList: LinearLayout
    private lateinit var btnDownload: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var dlArea: View
    private lateinit var dlTitle: TextView
    private lateinit var btnPause: TextView
    private lateinit var btnCancel: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var emptyHistory: TextView
    private lateinit var fAll: TextView
    private lateinit var fVideo: TextView
    private lateinit var fAudio: TextView
    private lateinit var pageDownloader: View
    private lateinit var pagePlayer: View
    private lateinit var navDl: ImageView
    private lateinit var navMusic: ImageView
    private lateinit var player: PlayerPage
    private var page = 0

    private var fetchId = 0
    private var fetchRunnable: Runnable? = null
    private var info: Info? = null
    private var choice: Choice? = null
    private var selectedRow: View? = null
    private var histFilter = 0 // 0 all, 1 video, 2 audio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlBox = findViewById(R.id.urlBox)
        btnPaste = findViewById(R.id.btnPaste)
        btnBell = findViewById(R.id.btnBell)
        badge = findViewById(R.id.badge)
        statusTv = findViewById(R.id.status)
        infoArea = findViewById(R.id.infoArea)
        thumb = findViewById(R.id.thumb)
        titleTv = findViewById(R.id.titleTv)
        metaTv = findViewById(R.id.metaTv)
        videoList = findViewById(R.id.videoList)
        audioList = findViewById(R.id.audioList)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        dlArea = findViewById(R.id.dlArea)
        dlTitle = findViewById(R.id.dlTitle)
        btnPause = findViewById(R.id.btnPause)
        btnCancel = findViewById(R.id.btnCancel)
        historyList = findViewById(R.id.historyList)
        emptyHistory = findViewById(R.id.emptyHistory)
        fAll = findViewById(R.id.fAll)
        fVideo = findViewById(R.id.fVideo)
        fAudio = findViewById(R.id.fAudio)
        pageDownloader = findViewById(R.id.pageDownloader)
        pagePlayer = findViewById(R.id.pagePlayer)
        navDl = findViewById(R.id.navDl)
        navMusic = findViewById(R.id.navMusic)
        player = PlayerPage(this, pagePlayer) { showLogs() }
        navDl.setOnClickListener { showPage(0) }
        navMusic.setOnClickListener { showPage(1) }
        showPage(0)

        urlBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onUrlChanged(s?.toString()?.trim() ?: "")
            }
        })
        btnPaste.setOnClickListener {
            if (urlBox.text.isNotEmpty()) urlBox.setText("") else pasteFromClipboard()
        }
        btnBell.setOnClickListener { showLogs() }
        btnDownload.setOnClickListener { startDownload() }
        btnPause.setOnClickListener {
            if (Downloader.state == Downloader.State.RUNNING) Downloader.pause()
            else if (Downloader.state == Downloader.State.PAUSED) Downloader.resume()
        }
        btnCancel.setOnClickListener { Downloader.cancel() }

        fAll.setOnClickListener { setFilter(0) }
        fVideo.setOnClickListener { setFilter(1) }
        fAudio.setOnClickListener { setFilter(2) }
        setFilter(0)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun showPage(p: Int) {
        page = p
        pageDownloader.visibility = if (p == 0) View.VISIBLE else View.GONE
        pagePlayer.visibility = if (p == 1) View.VISIBLE else View.GONE
        navDl.isSelected = p == 0
        navMusic.isSelected = p == 1
        val on = Color.parseColor("#FF2D46")
        val off = Color.parseColor("#8A8A92")
        navDl.setColorFilter(if (p == 0) on else off)
        navMusic.setColorFilter(if (p == 1) on else off)
    }

    override fun onStart() {
        super.onStart()
        player.onStart()
    }

    override fun onStop() {
        super.onStop()
        player.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page == 1 && player.handleBack()) return
        if (page == 1) { showPage(0); return }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        Downloader.listener = { main.post { syncDownloadUi() } }
        syncDownloadUi()
    }

    override fun onPause() {
        super.onPause()
        Downloader.listener = null
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) urlBox.setText(extractUrl(t.trim()))
        }
    }

    private fun extractUrl(t: String): String = Regex("https?://\\S+").find(t)?.value ?: t

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (t.isNotBlank()) urlBox.setText(extractUrl(t.trim()))
        else Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- status / logs

    private fun setStatus(text: String, error: Boolean = false, ok: Boolean = false) {
        statusTv.text = text
        statusTv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        statusTv.setTextColor(
            when {
                error -> Color.parseColor("#FF6B7F")
                ok -> Color.parseColor("#4CD08A")
                else -> Color.parseColor("#A0A0A8")
            }
        )
    }

    private fun addLog(text: String) {
        Logs.add(text)
        badge.visibility = View.VISIBLE
    }

    private fun fail(msg: String, e: Exception) {
        addLog("$msg\n${e.message ?: e.toString()}")
        setStatus("$msg. Tap the bell icon to see details.", error = true)
    }

    private fun showLogs() {
        badge.visibility = View.GONE
        Logs.unread = false
        player.refreshBadge()
        val tv = TextView(this).apply {
            text = Logs.get().ifEmpty { "No notifications yet." }
            setTextIsSelectable(true)
            setPadding(48, 32, 48, 32)
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Notifications")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> Logs.clear() }
            .setNegativeButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MyTube log", Logs.get()))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------------------------------------------------------------- link -> info

    private fun onUrlChanged(text: String) {
        btnPaste.setImageResource(if (text.isEmpty()) R.drawable.ic_paste else R.drawable.ic_close)
        fetchRunnable?.let { main.removeCallbacks(it) }
        fetchId++
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            setStatus(if (app.updated) "Loading video info..." else "Preparing engine (first time takes a bit)...")
            val r = Runnable { fetchInfo(text) }
            fetchRunnable = r
            main.postDelayed(r, 600)
        } else {
            hideInfo()
            setStatus(if (text.isEmpty()) "" else "Paste a full link starting with https://", error = text.isNotEmpty())
        }
    }

    private fun hideInfo() {
        infoArea.visibility = View.GONE
        info = null
        choice = null
        selectedRow = null
    }

    private fun fetchInfo(link: String) {
        val id = fetchId
        Thread {
            try {
                app.ensureEngine()
                if (id != fetchId) return@Thread
                val req = YoutubeDLRequest(link)
                req.addOption("--dump-single-json")
                req.addOption("--no-playlist")
                val resp = YoutubeDL.getInstance().execute(req)
                val out = resp.out
                val json = JSONObject(out.substring(out.indexOf('{')))
                val parsed = parseInfo(link, json)
                main.post { if (id == fetchId) showInfo(parsed) }
            } catch (e: Exception) {
                main.post {
                    if (id == fetchId) {
                        hideInfo()
                        fail("Could not load this link", e)
                    }
                }
            }
        }.start()
    }

    private fun JSONObject.str(key: String): String {
        val v = optString(key, "")
        return if (v == "null") "" else v
    }

    private fun parseInfo(link: String, json: JSONObject): Info {
        var dur = json.optDouble("duration", 0.0)
        if (dur.isNaN()) dur = 0.0

        val vids = ArrayList<Triple<Int, Long, Boolean>>()
        var audioBest = 0L
        val fmts = json.optJSONArray("formats")
        if (fmts != null) {
            for (k in 0 until fmts.length()) {
                val f = fmts.optJSONObject(k) ?: continue
                val vc = f.str("vcodec")
                val ac = f.str("acodec")
                val hasV = vc.isNotEmpty() && vc != "none"
                val hasA = ac.isNotEmpty() && ac != "none"
                var size = f.optLong("filesize", 0L)
                if (size <= 0) size = f.optLong("filesize_approx", 0L)
                if (size <= 0) {
                    val tbr = f.optDouble("tbr", 0.0)
                    if (tbr > 0 && dur > 0) size = (tbr * 125.0 * dur).toLong()
                }
                val h = f.optInt("height", 0)
                if (hasV && h > 0) vids.add(Triple(h, size, hasA))
                else if (hasA && !hasV) audioBest = maxOf(audioBest, size)
            }
        }

        val byH = HashMap<Int, Long>()
        for ((h, s, a) in vids) {
            val total = if (a || s <= 0) s else s + audioBest
            val cur = byH[h]
            if (cur == null || total > cur) byH[h] = total
        }
        val opts = byH.keys.sortedDescending().map { VOpt(it, byH[it] ?: 0L) }.toMutableList()
        if (opts.isEmpty()) opts.add(VOpt(0, 0L))

        var thumbUrl = json.str("thumbnail")
        if (thumbUrl.isEmpty()) {
            val ts: JSONArray? = json.optJSONArray("thumbnails")
            if (ts != null && ts.length() > 0) {
                thumbUrl = ts.optJSONObject(ts.length() - 1)?.str("url") ?: ""
            }
        }

        return Info(
            url = link,
            title = json.str("title").ifEmpty { "Video" },
            uploader = json.str("uploader"),
            duration = dur.toLong(),
            thumb = thumbUrl,
            video = opts,
            audioKbps = listOf(128, 192, 320)
        )
    }

    private fun showInfo(i: Info) {
        info = i
        choice = null
        selectedRow = null
        infoArea.visibility = View.VISIBLE
        setStatus("")
        titleTv.text = i.title
        metaTv.text = listOf(i.uploader, fmtDur(i.duration)).filter { it.isNotEmpty() }.joinToString(" • ")
        thumb.setImageDrawable(null)
        if (i.thumb.isNotEmpty()) loadThumb(i.thumb)

        videoList.removeAllViews()
        audioList.removeAllViews()
        var defaultRow: View? = null
        var defaultChoice: Choice? = null
        var defaultLabel = ""
        var first = true

        for (v in i.video) {
            val label = if (v.height > 0) "${v.height}p" else "Best quality"
            val size = if (v.size > 0) "~" + fmtSize(v.size) else "Size unknown"
            val row = addRow(videoList, label, "MP4", size)
            val c = Choice.Video(v.height)
            row.setOnClickListener { select(row, c, label) }
            if (first || v.height == 720) {
                if (first || defaultChoice == null || v.height == 720) {
                    defaultRow = row; defaultChoice = c; defaultLabel = label
                }
            }
            first = false
        }
        for (k in i.audioKbps) {
            val label = "MP3 $k kbps"
            val size = if (i.duration > 0) "~" + fmtSize(i.duration * k * 125L) else "Size unknown"
            val row = addRow(audioList, "MP3", "$k kbps", size)
            val c = Choice.Audio(k)
            row.setOnClickListener { select(row, c, label) }
        }
        if (defaultRow != null && defaultChoice != null) select(defaultRow, defaultChoice, defaultLabel)
    }

    private fun addRow(parent: LinearLayout, title: String, sub: String, size: String): View {
        val row = layoutInflater.inflate(R.layout.item_quality, parent, false)
        row.findViewById<TextView>(R.id.qTitle).text = title
        row.findViewById<TextView>(R.id.qSub).text = sub
        row.findViewById<TextView>(R.id.qSize).text = size
        parent.addView(row)
        return row
    }

    private fun select(row: View, c: Choice, label: String) {
        selectedRow?.isSelected = false
        row.isSelected = true
        selectedRow = row
        choice = c
        btnDownload.text = "Download $label"
    }

    private fun loadThumb(u: String) {
        Thread {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                main.post { if (info?.thumb == u && bmp != null) thumb.setImageBitmap(bmp) }
            } catch (e: Exception) {
                // thumbnail is optional
            }
        }.start()
    }

    // ---------------------------------------------------------------- download

    private fun startDownload() {
        val i = info ?: return
        val c = choice ?: return
        if (Downloader.state != Downloader.State.IDLE) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setStatus("")
        Downloader.start(this, i, c)
    }

    private fun syncDownloadUi() {
        badge.visibility = if (Logs.unread) View.VISIBLE else View.GONE
        if (Downloader.state == Downloader.State.IDLE) {
            dlArea.visibility = View.GONE
            btnDownload.isEnabled = true
            val fs = Downloader.finalStatus
            if (fs != null) {
                setStatus(fs, error = Downloader.finalError, ok = !Downloader.finalError)
                Downloader.finalStatus = null
            }
            renderHistory()
        } else {
            dlArea.visibility = View.VISIBLE
            btnDownload.isEnabled = false
            dlTitle.text = Downloader.title
            progressBar.progress = Downloader.pct
            progressText.text = Downloader.progressText
            btnPause.text = if (Downloader.state == Downloader.State.PAUSED) "Resume" else "Pause"
        }
    }

    // ---------------------------------------------------------------- history

    private fun setFilter(f: Int) {
        histFilter = f
        fAll.isSelected = f == 0
        fVideo.isSelected = f == 1
        fAudio.isSelected = f == 2
        renderHistory()
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val all = HistoryStore.load(this)
        val list = all.filter {
            when (histFilter) {
                1 -> !it.audio
                2 -> it.audio
                else -> true
            }
        }
        emptyHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val df = SimpleDateFormat("dd MMM, HH:mm", Locale.US)
        for (h in list) {
            val row = layoutInflater.inflate(R.layout.item_history, historyList, false)
            row.findViewById<ImageView>(R.id.hIcon).setImageResource(if (h.audio) R.drawable.ic_music else R.drawable.ic_video)
            row.findViewById<TextView>(R.id.hTitle).text = h.title
            row.findViewById<TextView>(R.id.hSub).text =
                (if (h.audio) "MP3" else "Video") + " • " + fmtSize(h.size) + " • " + df.format(Date(h.time))
            row.setOnClickListener { openFile(h) }
            row.setOnLongClickListener {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Remove \"${h.title}\" from this list? (The file stays in your Downloads folder.)")
                    .setPositiveButton("Remove") { _, _ ->
                        HistoryStore.save(this, HistoryStore.load(this).filter { it.uri != h.uri })
                        renderHistory()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            historyList.addView(row)
        }
    }

    private fun openFile(h: Hist) {
        val uri = Uri.parse(h.uri)
        try {
            contentResolver.openInputStream(uri)?.close()
        } catch (e: Exception) {
            Toast.makeText(this, "File not found (deleted?)", Toast.LENGTH_SHORT).show()
            HistoryStore.save(this, HistoryStore.load(this).filter { it.uri != h.uri })
            renderHistory()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, h.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun fmtSize(b: Long): String = when {
        b <= 0 -> "?"
        b >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", b / 1e9)
        else -> String.format(Locale.US, "%.1f MB", b / 1e6)
    }

    private fun fmtDur(s: Long): String {
        if (s <= 0) return ""
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
