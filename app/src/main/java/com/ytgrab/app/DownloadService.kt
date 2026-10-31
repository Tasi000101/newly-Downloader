package com.ytgrab.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_CANCEL = "cancel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // must call startForeground quickly after startForegroundService()
        startForeground(
            Downloader.NOTIF_ID,
            Downloader.buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        when (intent?.action) {
            ACTION_PAUSE -> Downloader.pause()
            ACTION_RESUME -> Downloader.resume()
            ACTION_CANCEL -> Downloader.cancel()
        }
        if (Downloader.state == Downloader.State.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
