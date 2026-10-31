package com.ytgrab.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app notification log (the bell icon). */
object Logs {
    private val sb = StringBuilder()
    @Volatile var unread = false

    @Synchronized fun add(text: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sb.append("[").append(t).append("] ").append(text).append("\n\n")
        if (sb.length > 20000) sb.delete(0, sb.length - 20000)
        unread = true
    }

    @Synchronized fun get(): String = sb.toString()
    @Synchronized fun clear() { sb.setLength(0) }
}

object HistoryStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ytgrab", Context.MODE_PRIVATE)

    @Synchronized fun load(ctx: Context): MutableList<Hist> {
        val out = ArrayList<Hist>()
        try {
            val arr = JSONArray(prefs(ctx).getString("items", "[]"))
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                out.add(Hist(o.getString("title"), o.getString("uri"), o.getString("mime"),
                    o.getBoolean("audio"), o.getLong("size"), o.getLong("time")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    @Synchronized fun save(ctx: Context, list: List<Hist>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(JSONObject().put("title", h.title).put("uri", h.uri).put("mime", h.mime)
                .put("audio", h.audio).put("size", h.size).put("time", h.time))
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    @Synchronized fun add(ctx: Context, items: List<Hist>) {
        val l = load(ctx)
        l.addAll(0, items)
        save(ctx, l.take(100))
    }
}

/** Runs one download at a time. Supports pause (stop process, keep partial files), resume and cancel. */
object Downloader {
    enum class State { IDLE, RUNNING, PAUSED }

    const val NOTIF_ID = 1
    private const val DONE_ID = 2
    private const val PROCESS_ID = "ytgrab"

    @Volatile var state = State.IDLE
    @Volatile var pct = 0
    @Volatile var progressText = ""
    @Volatile var title = ""
    @Volatile var finalStatus: String? = null
    @Volatile var finalError = false
    @Volatile var listener: (() -> Unit)? = null

    private var appCtx: Context? = null
    private var info: Info? = null
    private var choice: Choice? = null
    private var tmp: File? = null
    @Volatile private var stopReason = 0 // 0 none, 1 pause, 2 cancel
    private var lastNotify = 0L

    private fun changed() { listener?.invoke() }

    @Synchronized
    fun start(ctx: Context, i: Info, c: Choice) {
        if (state != State.IDLE) return
        appCtx = ctx.applicationContext
        info = i
        choice = c
        title = i.title
        tmp = File(ctx.cacheDir, "dl-${System.currentTimeMillis()}").apply { mkdirs() }
        pct = 0
        progressText = "Starting..."
        finalStatus = null
        finalError = false
        state = State.RUNNING
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_START))
        launch()
        changed()
    }

    fun pause() {
        if (state != State.RUNNING) return
        stopReason = 1
        progressText = "Pausing..."
        changed()
        try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (e: Exception) {}
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.RUNNING
        progressText = "Resuming..."
        launch()
        changed()
        updateNotification(true)
    }

    fun cancel() {
        when (state) {
            State.RUNNING -> {
                stopReason = 2
                progressText = "Cancelling..."
                changed()
                try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (e: Exception) {}
            }
            State.PAUSED -> {
                stopReason = 2
                finish("Download cancelled", false, null)
            }
            else -> {}
        }
    }

    private fun launch() {
        stopReason = 0
        Thread { runDownload() }.start()
    }

    private fun runDownload() {
        val ctx = appCtx ?: return
        val i = info ?: return
        val c = choice ?: return
        val dir = tmp ?: return
        try {
            (ctx as App).ensureEngine()
            if (stopReason != 0) throw RuntimeException("stopped")

            val req = YoutubeDLRequest(i.url)
            req.addOption("--no-playlist")
            req.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
            when (c) {
                is Choice.Audio -> {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "${c.kbps}K")
                }
                is Choice.Video -> {
                    val h = c.height
                    val f = if (h > 0)
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]"
                    else
                        "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b"
                    req.addOption("-f", f)
                    req.addOption("--merge-output-format", "mp4")
                }
            }

            YoutubeDL.getInstance().execute(req, PROCESS_ID) { p, eta, _ ->
                if (p >= 0) pct = p.toInt().coerceIn(0, 100)
                progressText = if (p < 0) "Preparing..." else
                    "$pct%" + (if (eta > 0) " • ${eta}s left" else "")
                changed()
                updateNotification(false)
            }

            if (stopReason == 2) { finish("Download cancelled", false, null); return }

            progressText = "Saving to your phone..."
            changed()
            updateNotification(true)
            val files = dir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            } ?: emptyList()
            if (files.isEmpty()) throw RuntimeException("No file was produced")
            val saved = files.map { f ->
                Hist(f.nameWithoutExtension, saveToDownloads(ctx, f).toString(), mimeOf(f),
                    isAudio(f), f.length(), System.currentTimeMillis())
            }
            HistoryStore.add(ctx, saved)
            finish("Saved to Downloads/MyTube", false, "Download complete")
        } catch (e: Exception) {
            when (stopReason) {
                1 -> {
                    state = State.PAUSED
                    progressText = "Paused • $pct%"
                    changed()
                    updateNotification(true)
                }
                2 -> finish("Download cancelled", false, null)
                else -> {
                    Logs.add("Download failed\n${e.message ?: e.toString()}")
                    finish("Download failed. Tap the bell icon to see details.", true, "Download failed")
                }
            }
        }
    }

    private fun finish(msg: String?, error: Boolean, doneTitle: String?) {
        tmp?.deleteRecursively()
        tmp = null
        finalStatus = msg
        finalError = error
        state = State.IDLE
        pct = 0
        val c = appCtx
        if (c != null) {
            c.stopService(Intent(c, DownloadService::class.java))
            if (doneTitle != null) notifyDone(c, doneTitle)
        }
        changed()
    }

    // ------------------------------------------------------------ notifications

    private fun openAppIntent(c: Context) = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(c: Context, label: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            c, req, Intent(c, DownloadService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_download), label, pi).build()
    }

    fun buildNotification(c: Context): Notification {
        val b = Notification.Builder(c, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (title.isEmpty()) "MyTube" else title)
            .setContentText(progressText)
            .setContentIntent(openAppIntent(c))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setColor(0xFFFF2D46.toInt())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (state == State.PAUSED) {
            b.setProgress(100, pct, false)
            b.addAction(action(c, "Resume", DownloadService.ACTION_RESUME, 2))
        } else {
            b.setProgress(100, pct, pct <= 0)
            b.addAction(action(c, "Pause", DownloadService.ACTION_PAUSE, 1))
        }
        b.addAction(action(c, "Cancel", DownloadService.ACTION_CANCEL, 3))
        return b.build()
    }

    private fun updateNotification(force: Boolean) {
        val c = appCtx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        if (state == State.IDLE) return
        try {
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(c))
        } catch (e: Exception) {
        }
    }

    private fun notifyDone(c: Context, headline: String) {
        try {
            val n = Notification.Builder(c, App.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(headline)
                .setContentText(title)
                .setContentIntent(openAppIntent(c))
                .setAutoCancel(true)
                .setColor(0xFFFF2D46.toInt())
                .build()
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(DONE_ID, n)
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ saving

    private fun isAudio(f: File) = f.extension.lowercase() in setOf("mp3", "m4a", "opus", "ogg", "wav", "aac", "flac")

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
    }

    private fun saveToDownloads(ctx: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyTube")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Could not create the file in Downloads")
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return uri
    }
}
