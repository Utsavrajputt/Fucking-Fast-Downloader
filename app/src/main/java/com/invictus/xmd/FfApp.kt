package com.invictus.xmd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.invictus.xmd.core.BookmarkRepository
import com.invictus.xmd.core.HistoryRepository
import com.invictus.xmd.core.QueueRepository
import com.invictus.xmd.core.Settings

class FfApp : Application() {

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "ff_downloads"
    }

    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        // Loads the previously-persisted queue from disk so it survives
        // process restart (see QueueRepository's persistence docs).
        QueueRepository.init(this)
        BookmarkRepository.init(this)
        HistoryRepository.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
