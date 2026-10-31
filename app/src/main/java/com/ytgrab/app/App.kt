package com.ytgrab.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch

class App : Application() {
    private val initLatch = CountDownLatch(1)
    @Volatile private var initError: String? = null
    @Volatile var updated = false
        private set
    private var attempts = 0

    companion object {
        const val CHANNEL = "downloads"
    }

    override fun onCreate() {
        super.onCreate()
        MusicStore.init(this)
        val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        Thread {
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
            } catch (e: Exception) {
                initError = e.message ?: e.toString()
            } finally {
                initLatch.countDown()
            }
        }.start()
    }

    /** Waits for init, and updates yt-dlp to the latest version once. Call from a background thread. */
    @Synchronized
    fun ensureEngine() {
        initLatch.await()
        initError?.let { throw RuntimeException("Engine init failed: $it") }
        if (!updated && attempts < 2) {
            attempts++
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._NIGHTLY)
                updated = true
            } catch (e: Exception) {
                // offline / GitHub unreachable: keep using the bundled version
            }
        }
    }
}
