package com.ytgrab.app

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Turns a page link (YouTube etc.) into a direct audio stream URL using yt-dlp, with a short cache. */
@OptIn(UnstableApi::class)
object StreamResolver {
    lateinit var app: App
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun resolve(url: String): String {
        val now = System.currentTimeMillis()
        val hit = cache[url]
        if (hit != null && hit.second > now) return hit.first
        app.ensureEngine()
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        req.addOption("-g")
        val out = YoutubeDL.getInstance().execute(req).out
        val stream = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
            ?: throw IOException("No stream URL found")
        cache[url] = Pair(stream, now + 20 * 60 * 1000L)
        return stream
    }

    val resolver = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val stream = try {
                resolve(dataSpec.uri.toString())
            } catch (e: Exception) {
                throw IOException(e.message ?: "Could not get the audio stream", e)
            }
            return dataSpec.withUri(Uri.parse(stream))
        }
    }
}

/** Background music playback: keeps playing with the screen off or after the app is swiped away. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        StreamResolver.app = application as App

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val dataSource = ResolvingDataSource.Factory(http, StreamResolver.resolver)

        // big buffer so playback stays smooth on a weak connection
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_000, 5_000)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                prefetchNext(player)
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    /** resolve the next song's stream URL early so the gap between songs is tiny */
    private fun prefetchNext(p: Player) {
        val i = p.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val url = p.getMediaItemAt(i).localConfiguration?.uri?.toString() ?: return
        Thread {
            try { StreamResolver.resolve(url) } catch (e: Exception) { }
        }.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // keep playing when the app is swiped away; only stop if nothing is playing
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
