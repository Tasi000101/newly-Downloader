package com.ytgrab.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.util.Locale

/** The "Music" tab: stream songs/playlists online, favorites, custom playlists. */
class PlayerPage(
    private val act: Activity,
    private val root: View,
    private val onBell: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val inflater = LayoutInflater.from(act)

    private fun <T : View> v(id: Int): T = root.findViewById(id)

    private val pBell: View = v(R.id.pBell)
    private val pBadge: View = v(R.id.pBadge)
    private val nowCard: View = v(R.id.pNow)
    private val pArt: ImageView = v(R.id.pArt)
    private val pTitle: TextView = v(R.id.pTitle)
    private val pArtist: TextView = v(R.id.pArtist)
    private val pSeek: SeekBar = v(R.id.pSeek)
    private val pCur: TextView = v(R.id.pCur)
    private val pTotal: TextView = v(R.id.pTotal)
    private val pFav: ImageView = v(R.id.pFav)
    private val pPrev: ImageView = v(R.id.pPrev)
    private val pPlay: ImageView = v(R.id.pPlay)
    private val pNext: ImageView = v(R.id.pNext)
    private val pStop: ImageView = v(R.id.pStop)
    private val pUrl: EditText = v(R.id.pUrl)
    private val pPaste: ImageView = v(R.id.pPaste)
    private val pStatus: TextView = v(R.id.pStatus)
    private val pResult: LinearLayout = v(R.id.pResult)
    private val pFavTab: TextView = v(R.id.pFavTab)
    private val pPlTab: TextView = v(R.id.pPlTab)
    private val pLib: LinearLayout = v(R.id.pLib)

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var listing: Listing? = null
    private var curId = ""
    private var lastRenderedId = ""
    private var dragging = false
    private var fetchId = 0
    private var fetchRunnable: Runnable? = null
    private var libTab = 0
    private var openPlaylistId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync() }

        override fun onPlayerError(error: PlaybackException) {
            Logs.add("Playback error\n${error.errorCodeName}\n${error.message ?: ""}\n${error.cause?.message ?: ""}")
            refreshBadge()
            val c = controller ?: return
            if (c.hasNextMediaItem()) {
                setStatus("Skipped a song that couldn't be played. Tap the bell icon for details.", true)
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            } else {
                setStatus("Couldn't play this song. Tap the bell icon for details.", true)
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, 500)
        }
    }

    init {
        pUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onUrlChanged(s?.toString()?.trim() ?: "") }
        })
        pPaste.setOnClickListener { if (pUrl.text.isNotEmpty()) pUrl.setText("") else paste() }
        pBell.setOnClickListener { onBell() }
        pPlay.setOnClickListener { togglePlay() }
        pPrev.setOnClickListener { controller?.seekToPreviousMediaItem() }
        pNext.setOnClickListener { controller?.seekToNextMediaItem() }
        pStop.setOnClickListener {
            controller?.let { it.stop(); it.clearMediaItems() }
            sync()
        }
        pFav.setOnClickListener {
            val item = controller?.currentMediaItem
            if (item != null) {
                MusicStore.toggleFav(item.toTrack())
                sync(); renderResults(); renderLibrary()
            }
        }
        pSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) pCur.text = fmt(p * 1000L)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                controller?.seekTo((sb?.progress ?: 0) * 1000L)
            }
        })
        pFavTab.setOnClickListener { libTab = 0; openPlaylistId = null; renderLibrary() }
        pPlTab.setOnClickListener { libTab = 1; openPlaylistId = null; renderLibrary() }
        renderLibrary()
    }

    // ------------------------------------------------------------------ lifecycle

    fun onStart() {
        val token = SessionToken(act, ComponentName(act, PlaybackService::class.java))
        val f = MediaController.Builder(act, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                sync()
            } catch (e: Exception) {
                Logs.add("Player connection failed\n${e.message ?: e.toString()}")
                refreshBadge()
            }
        }, ContextCompat.getMainExecutor(act))
        main.removeCallbacks(ticker)
        main.post(ticker)
        refreshBadge()
    }

    fun onStop() {
        main.removeCallbacks(ticker)
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
    }

    fun handleBack(): Boolean {
        if (libTab == 1 && openPlaylistId != null) {
            openPlaylistId = null
            renderLibrary()
            return true
        }
        return false
    }

    fun refreshBadge() {
        pBadge.visibility = if (Logs.unread) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(x: Int) = (x * act.resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun setStatus(text: String, error: Boolean = false) {
        pStatus.text = text
        pStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        pStatus.setTextColor(Color.parseColor(if (error) "#FF6B7F" else "#A0A0A8"))
    }

    private fun fail(msg: String, e: Exception) {
        Logs.add("$msg\n${e.message ?: e.toString()}")
        refreshBadge()
        setStatus("$msg. Tap the bell icon for details.", true)
    }

    private fun look(view: View, on: Boolean) {
        view.isEnabled = on
        view.alpha = if (on) 1f else 0.3f
    }

    private fun extractUrl(t: String): String = Regex("https?://\\S+").find(t)?.value ?: t

    private fun paste() {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(act).toString() else ""
        if (t.isNotBlank()) pUrl.setText(extractUrl(t.trim()))
        else Toast.makeText(act, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ now playing

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying || c.playWhenReady) c.pause() else { c.prepare(); c.play() }
    }

    private fun sync() {
        refreshBadge()
        val c = controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            nowCard.visibility = View.GONE
            curId = ""
        } else {
            nowCard.visibility = View.VISIBLE
            curId = item.mediaId
            val t = item.toTrack()
            pTitle.text = t.title
            pArtist.text = t.artist
            if (pArt.tag != t.thumb) Images.load(t.thumb, pArt)
            pPlay.setImageResource(if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) R.drawable.ic_pause else R.drawable.ic_play)
            look(pPrev, c.hasPreviousMediaItem())
            look(pNext, c.hasNextMediaItem())
            pFav.setImageResource(if (MusicStore.isFav(t.id)) R.drawable.ic_heart_on else R.drawable.ic_heart)
            tick()
        }
        if (curId != lastRenderedId) {
            lastRenderedId = curId
            renderResults()
            renderLibrary()
        }
    }

    private fun tick() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        if (dragging) return
        val d = c.duration
        val totalMs = if (d != C.TIME_UNSET && d > 0) d else (item.mediaMetadata.extras?.getLong("dur") ?: 0L) * 1000
        pSeek.max = (totalMs / 1000).toInt().coerceAtLeast(1)
        pSeek.progress = (c.currentPosition / 1000).toInt().coerceIn(0, pSeek.max)
        val loading = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING
        pCur.text = if (loading) "Loading..." else fmt(c.currentPosition)
        pTotal.text = fmt(totalMs)
    }

    private fun play(list: List<Track>, index: Int) {
        val c = controller
        if (c == null) {
            Toast.makeText(act, "Player is starting, try again in a moment", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
        setStatus("")
    }

    // ------------------------------------------------------------------ link -> tracks

    private fun onUrlChanged(text: String) {
        pPaste.setImageResource(if (text.isEmpty()) R.drawable.ic_paste else R.drawable.ic_close)
        fetchRunnable?.let { main.removeCallbacks(it) }
        fetchId++
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            setStatus("Loading...")
            val r = Runnable { fetch(text) }
            fetchRunnable = r
            main.postDelayed(r, 600)
        } else {
            listing = null
            renderResults()
            setStatus(if (text.isEmpty()) "" else "Paste a full link starting with https://", text.isNotEmpty())
        }
    }

    private fun fetch(link: String) {
        val id = fetchId
        Thread {
            try {
                (act.application as App).ensureEngine()
                if (id != fetchId) return@Thread
                val isList = link.contains("list=") || link.contains("/playlist")
                val req = YoutubeDLRequest(link)
                req.addOption("-J")
                if (isList) {
                    req.addOption("--flat-playlist")
                    req.addOption("--yes-playlist")
                    req.addOption("--playlist-end", "200")
                } else {
                    req.addOption("--no-playlist")
                }
                val out = YoutubeDL.getInstance().execute(req).out
                val json = JSONObject(out.substring(out.indexOf('{')))
                val result = parse(link, json)
                main.post {
                    if (id == fetchId) {
                        listing = result
                        setStatus("")
                        renderResults()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (id == fetchId) {
                        listing = null
                        renderResults()
                        fail("Could not load this link", e)
                    }
                }
            }
        }.start()
    }

    private fun JSONObject.str(key: String): String {
        val s = optString(key, "")
        return if (s == "null") "" else s
    }

    private fun parse(link: String, j: JSONObject): Listing {
        val entries = j.optJSONArray("entries")
        if (entries != null) {
            val list = ArrayList<Track>()
            for (k in 0 until entries.length()) {
                val e = entries.optJSONObject(k) ?: continue
                val id = e.str("id")
                val title = e.str("title")
                if (id.isEmpty() || title == "[Private video]" || title == "[Deleted video]") continue
                val isYt = e.str("ie_key").startsWith("Youtube") || e.str("url").contains("youtu")
                val url = if (isYt) "https://www.youtube.com/watch?v=$id" else e.str("url").ifEmpty { link }
                val thumb = if (isYt) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else e.str("thumbnail")
                val artist = e.str("channel").ifEmpty { e.str("uploader") }
                list.add(Track(id, url, title.ifEmpty { "Untitled" }, artist, thumb, e.optDouble("duration", 0.0).toLong()))
            }
            if (list.isEmpty()) throw RuntimeException("This playlist has no playable songs")
            val v = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(link)?.groupValues?.get(1)
            val start = if (v == null) 0 else list.indexOfFirst { it.id == v }.coerceAtLeast(0)
            return Listing(j.str("title").ifEmpty { "Playlist" }, list, true, start)
        }
        val id = j.str("id").ifEmpty { link.hashCode().toString() }
        val isYt = j.str("extractor_key").startsWith("Youtube")
        val url = if (isYt) "https://www.youtube.com/watch?v=$id" else j.str("webpage_url").ifEmpty { link }
        val thumb = if (isYt) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else j.str("thumbnail")
        val artist = j.str("artist").ifEmpty { j.str("channel").ifEmpty { j.str("uploader") } }
        val t = Track(id, url, j.str("title").ifEmpty { "Untitled" }, artist, thumb, j.optDouble("duration", 0.0).toLong())
        return Listing(t.title, listOf(t), false, 0)
    }

    // ------------------------------------------------------------------ rendering

    private fun trackRow(parent: LinearLayout, t: Track, onPlay: () -> Unit, onRemove: (() -> Unit)?) {
        val row = inflater.inflate(R.layout.item_track, parent, false)
        val title = row.findViewById<TextView>(R.id.tTitle)
        title.text = t.title
        title.setTextColor(if (t.id == curId) Color.parseColor("#FF2D46") else Color.WHITE)
        row.findViewById<TextView>(R.id.tSub).text =
            listOf(t.artist, if (t.duration > 0) fmt(t.duration * 1000) else "").filter { it.isNotEmpty() }.joinToString(" • ")
        Images.load(t.thumb, row.findViewById(R.id.tThumb))

        val fav = row.findViewById<ImageView>(R.id.tFav)
        fav.setImageResource(if (MusicStore.isFav(t.id)) R.drawable.ic_heart_on else R.drawable.ic_heart)
        fav.setOnClickListener {
            MusicStore.toggleFav(t)
            sync(); renderResults(); renderLibrary()
        }
        val act2 = row.findViewById<ImageView>(R.id.tAction)
        if (onRemove != null) {
            act2.setImageResource(R.drawable.ic_close)
            act2.setOnClickListener { onRemove() }
        } else {
            act2.setImageResource(R.drawable.ic_playlist_add)
            act2.setOnClickListener { addToPlaylistDialog(t) }
        }
        row.setOnClickListener { onPlay() }
        parent.addView(row)
    }

    private fun addHeader(
        parent: LinearLayout, title: String, sub: String, back: Boolean,
        aLabel: String, onA: () -> Unit, bLabel: String?, onB: (() -> Unit)?
    ) {
        val h = inflater.inflate(R.layout.item_list_header, parent, false)
        h.findViewById<TextView>(R.id.hTitle).text = title
        h.findViewById<TextView>(R.id.hSub).text = sub
        val back1 = h.findViewById<View>(R.id.hBack)
        back1.visibility = if (back) View.VISIBLE else View.GONE
        back1.setOnClickListener { handleBack() }
        val a = h.findViewById<Button>(R.id.hBtnA)
        a.text = aLabel
        a.setOnClickListener { onA() }
        val b = h.findViewById<Button>(R.id.hBtnB)
        if (bLabel == null || onB == null) b.visibility = View.GONE
        else { b.text = bLabel; b.setOnClickListener { onB() } }
        parent.addView(h)
    }

    private fun emptyText(parent: LinearLayout, text: String) {
        val tv = TextView(act)
        tv.text = text
        tv.setTextColor(Color.parseColor("#A0A0A8"))
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(24), 0, dp(24))
        parent.addView(tv)
    }

    private fun renderResults() {
        pResult.removeAllViews()
        val l = listing ?: return
        val top = (pResult.layoutParams as LinearLayout.LayoutParams)
        top.topMargin = dp(6)
        if (l.isPlaylist) {
            addHeader(pResult, l.title, "${l.tracks.size} songs", false,
                "Play all", { play(l.tracks, l.startIndex) },
                "Save as playlist") { savePlaylistDialog(l) }
        }
        l.tracks.forEachIndexed { i, t -> trackRow(pResult, t, { play(l.tracks, i) }, null) }
    }

    private fun renderLibrary() {
        pLib.removeAllViews()
        pFavTab.isSelected = libTab == 0
        pPlTab.isSelected = libTab == 1
        if (libTab == 0) {
            val favs = MusicStore.favorites()
            if (favs.isEmpty()) {
                emptyText(pLib, "No favorites yet.\nTap the heart on any song.")
                return
            }
            addHeader(pLib, "Favorites", "${favs.size} songs", false, "Play all", { play(favs, 0) }, null, null)
            favs.forEachIndexed { i, t -> trackRow(pLib, t, { play(favs, i) }, null) }
            return
        }
        val open = openPlaylistId?.let { MusicStore.getPlaylist(it) }
        if (open != null) {
            addHeader(pLib, open.name, "${open.tracks.size} songs", true,
                "Play all", { if (open.tracks.isNotEmpty()) play(open.tracks, 0) },
                "Delete") {
                AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage("Delete the playlist \"${open.name}\"? (Songs are not deleted from anywhere else.)")
                    .setPositiveButton("Delete") { _, _ ->
                        MusicStore.deletePlaylist(open.id)
                        openPlaylistId = null
                        renderLibrary()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            if (open.tracks.isEmpty()) emptyText(pLib, "This playlist is empty.\nUse the add icon on any song.")
            open.tracks.forEachIndexed { i, t ->
                trackRow(pLib, t, { play(open.tracks, i) }) {
                    MusicStore.removeFromPlaylist(open.id, t.id)
                    renderLibrary()
                }
            }
            return
        }
        openPlaylistId = null
        val nb = Button(act)
        nb.text = "+ New playlist"
        nb.isAllCaps = false
        nb.setTextColor(Color.parseColor("#FF6B7F"))
        nb.setBackgroundResource(R.drawable.bg_button_outline)
        nb.stateListAnimator = null
        nb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) }
        nb.setOnClickListener {
            askName("New playlist", "") { name -> MusicStore.createPlaylist(name); renderLibrary() }
        }
        pLib.addView(nb)
        val pls = MusicStore.playlists()
        if (pls.isEmpty()) emptyText(pLib, "No playlists yet.")
        for (p in pls) {
            val row = inflater.inflate(R.layout.item_track, pLib, false)
            row.findViewById<TextView>(R.id.tTitle).text = p.name
            row.findViewById<TextView>(R.id.tSub).text = "${p.tracks.size} songs"
            Images.load(p.tracks.firstOrNull()?.thumb, row.findViewById(R.id.tThumb))
            row.findViewById<View>(R.id.tFav).visibility = View.GONE
            val playBtn = row.findViewById<ImageView>(R.id.tAction)
            playBtn.setImageResource(R.drawable.ic_play)
            playBtn.setColorFilter(Color.parseColor("#FF2D46"))
            playBtn.setOnClickListener { if (p.tracks.isNotEmpty()) play(p.tracks, 0) }
            row.setOnClickListener { openPlaylistId = p.id; renderLibrary() }
            pLib.addView(row)
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun askName(title: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(act)
        et.setText(initial)
        et.setTextColor(Color.WHITE)
        et.setSingleLine()
        et.hint = "Playlist name"
        val box = FrameLayout(act)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) ok(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylistDialog(t: Track) {
        val pls = MusicStore.playlists()
        val names = pls.map { it.name } + "+ New playlist..."
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add to playlist")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == pls.size) {
                    askName("New playlist", "") { name ->
                        MusicStore.createPlaylist(name, listOf(t))
                        Toast.makeText(act, "Added to $name", Toast.LENGTH_SHORT).show()
                        renderLibrary()
                    }
                } else {
                    val added = MusicStore.addToPlaylist(pls[which].id, t)
                    Toast.makeText(act, if (added) "Added to ${pls[which].name}" else "Already in ${pls[which].name}", Toast.LENGTH_SHORT).show()
                    renderLibrary()
                }
            }
            .show()
    }

    private fun savePlaylistDialog(l: Listing) {
        askName("Save playlist", l.title) { name ->
            MusicStore.createPlaylist(name, l.tracks)
            Toast.makeText(act, "Saved \"$name\" (links only, no storage used)", Toast.LENGTH_LONG).show()
            libTab = 1
            openPlaylistId = null
            renderLibrary()
        }
    }
}
