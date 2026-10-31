package com.ytgrab.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Track(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val thumb: String,
    val duration: Long
)

data class Playlist(val id: String, val name: String, val tracks: List<Track>)

data class Listing(val title: String, val tracks: List<Track>, val isPlaylist: Boolean, val startIndex: Int)

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString("url", url)
        putString("thumb", thumb)
        putLong("dur", duration)
    }
    val md = MediaMetadata.Builder().setTitle(title).setArtist(artist).setExtras(extras)
    if (thumb.isNotEmpty()) md.setArtworkUri(Uri.parse(thumb))
    return MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(md.build()).build()
}

fun MediaItem.toTrack(): Track {
    val x = mediaMetadata.extras
    return Track(
        mediaId,
        x?.getString("url") ?: "",
        mediaMetadata.title?.toString() ?: "",
        mediaMetadata.artist?.toString() ?: "",
        x?.getString("thumb") ?: "",
        x?.getLong("dur") ?: 0L
    )
}

/** Favorites + user playlists. Only links/metadata are stored, never audio. */
object MusicStore {
    private var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }

    private fun prefs() = ctx!!.getSharedPreferences("music", Context.MODE_PRIVATE)

    private fun trackToJson(t: Track) = JSONObject().put("id", t.id).put("url", t.url).put("title", t.title)
        .put("artist", t.artist).put("thumb", t.thumb).put("dur", t.duration)

    private fun trackFromJson(o: JSONObject) = Track(
        o.optString("id"), o.optString("url"), o.optString("title"),
        o.optString("artist"), o.optString("thumb"), o.optLong("dur")
    )

    private fun readTracks(arr: JSONArray?): MutableList<Track> {
        val out = ArrayList<Track>()
        if (arr != null) for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            out.add(trackFromJson(o))
        }
        return out
    }

    // ---- favorites
    @Synchronized fun favorites(): MutableList<Track> =
        try { readTracks(JSONArray(prefs().getString("favs", "[]"))) } catch (e: Exception) { ArrayList() }

    @Synchronized private fun saveFavs(l: List<Track>) {
        val a = JSONArray(); l.forEach { a.put(trackToJson(it)) }
        prefs().edit().putString("favs", a.toString()).apply()
    }

    @Synchronized fun isFav(id: String) = favorites().any { it.id == id }

    /** returns true if the track is now a favorite */
    @Synchronized fun toggleFav(t: Track): Boolean {
        val l = favorites()
        val ex = l.indexOfFirst { it.id == t.id }
        return if (ex >= 0) { l.removeAt(ex); saveFavs(l); false } else { l.add(0, t); saveFavs(l); true }
    }

    // ---- playlists
    @Synchronized fun playlists(): MutableList<Playlist> {
        val out = ArrayList<Playlist>()
        try {
            val arr = JSONArray(prefs().getString("playlists", "[]"))
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                out.add(Playlist(o.getString("id"), o.getString("name"), readTracks(o.optJSONArray("tracks"))))
            }
        } catch (e: Exception) {
        }
        return out
    }

    @Synchronized private fun savePlaylists(l: List<Playlist>) {
        val arr = JSONArray()
        for (p in l) {
            val ts = JSONArray(); p.tracks.forEach { ts.put(trackToJson(it)) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", ts))
        }
        prefs().edit().putString("playlists", arr.toString()).apply()
    }

    @Synchronized fun getPlaylist(id: String): Playlist? = playlists().firstOrNull { it.id == id }

    @Synchronized fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val l = playlists()
        val p = Playlist(System.currentTimeMillis().toString(), name, tracks.distinctBy { it.id })
        l.add(p)
        savePlaylists(l)
        return p
    }

    @Synchronized fun addToPlaylist(pid: String, t: Track): Boolean {
        val l = playlists()
        val i = l.indexOfFirst { it.id == pid }
        if (i < 0) return false
        val p = l[i]
        if (p.tracks.any { it.id == t.id }) return false
        l[i] = p.copy(tracks = p.tracks + t)
        savePlaylists(l)
        return true
    }

    @Synchronized fun removeFromPlaylist(pid: String, tid: String) {
        val l = playlists()
        val i = l.indexOfFirst { it.id == pid }
        if (i < 0) return
        l[i] = l[i].copy(tracks = l[i].tracks.filter { it.id != tid })
        savePlaylists(l)
    }

    @Synchronized fun deletePlaylist(pid: String) {
        savePlaylists(playlists().filter { it.id != pid })
    }
}

/** Tiny image loader with an in-memory cache (for thumbnails). */
object Images {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, iv: ImageView) {
        iv.tag = url
        iv.setImageDrawable(null)
        if (url.isNullOrEmpty()) return
        val hit = cache.get(url)
        if (hit != null) { iv.setImageBitmap(hit); return }
        pool.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    main.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
                }
            } catch (e: Exception) {
            }
        }
    }
}
